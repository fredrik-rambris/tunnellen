package dev.rambris.tunnellen;

import ch.qos.logback.classic.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.loader.ClasspathLoader;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


public class Web {

    private static final Logger log = (Logger) LoggerFactory.getLogger(Web.class);

    private Configuration config;
    private HttpServer server;

    /**
     * Supplies liveness info for cluster (SOCKS) tunnels, keyed by context, e.g.
     * {@code {"bhg-dev": true, "bhg-prod": false}}. Deliberately NOT coupled to the
     * concrete {@code ClusterSocksTunnel} class (which doesn't exist in this worktree
     * as of step 4.3 — it's being introduced concurrently by steps 1.4/2.2 elsewhere)
     * so this file doesn't need to know about that type to compile or merge cleanly.
     * Defaults to a no-op supplier returning an empty map, so the status strip renders
     * nothing extra until real data is wired up.
     *
     * TODO(4.3 reconciliation): once ClusterSocksTunnel instances exist (steps
     * 1.4/2.2), Main.java should construct Web with a supplier here that reflects
     * their live isAlive() state, e.g.:
     *   () -> clusterTunnels.stream().collect(Collectors.toMap(
     *           ClusterSocksTunnel::getContext, ClusterSocksTunnel::isAlive))
     * This is expected to be a manual merge fixup, not automatic.
     */
    private final Supplier<Map<String, Boolean>> clusterTunnelStatus;

    /** Supplies context -&gt; number of connections currently being relayed through that cluster's SOCKS proxy. */
    private final Supplier<Map<String, Integer>> clusterConnectionCounts;

    /**
     * Looks up the actual {@link TunnelRunner} live for a config {@link Tunnel}'s
     * id -- for {@code mode: PORT_FORWARD} this is the {@code Tunnel} itself, but
     * for {@code mode: SERVICE} it's a separate {@link SocksServiceTunnel}
     * instance (see {@code Main#runningTunnels}), whose {@code isRunning()}/{@code
     * getLastCheck()} reflect the actual SOCKS relay's state rather than a
     * nonexistent per-tunnel {@code kubectl} process. Returns {@code null} for a
     * tunnel that's never been started (or isn't currently registered), in which
     * case callers fall back to the config {@code Tunnel} object's own (correctly
     * "not started") state.
     */
    private final Function<String, TunnelRunner> tunnelRunnerLookup;

    public Web(Configuration config) throws IOException, InterruptedException {
        this(config, Map::of, Map::of, id -> null);
    }

    /**
     * @param clusterTunnelStatus supplies context -&gt; isAlive for known cluster
     *                            (SOCKS) tunnels; pass {@code Map::of} (the default
     *                            used by {@link #Web(Configuration)}) if no cluster
     *                            tunnels are wired up yet.
     */
    public Web(Configuration config, Supplier<Map<String, Boolean>> clusterTunnelStatus) throws IOException, InterruptedException {
        this(config, clusterTunnelStatus, Map::of, id -> null);
    }

    /**
     * @param clusterTunnelStatus     see {@link #Web(Configuration, Supplier)}.
     * @param clusterConnectionCounts see {@link #clusterConnectionCounts}; pass
     *                                {@code Map::of} if not wired up.
     * @param tunnelRunnerLookup      see {@link #tunnelRunnerLookup}; pass {@code
     *                                id -> null} if not wired up (status then
     *                                falls back to the config {@code Tunnel}'s own
     *                                state, as before this parameter existed).
     */
    public Web(Configuration config, Supplier<Map<String, Boolean>> clusterTunnelStatus,
               Supplier<Map<String, Integer>> clusterConnectionCounts, Function<String, TunnelRunner> tunnelRunnerLookup)
            throws IOException, InterruptedException {
        this.config = config;
        this.clusterTunnelStatus = clusterTunnelStatus != null ? clusterTunnelStatus : Map::of;
        this.clusterConnectionCounts = clusterConnectionCounts != null ? clusterConnectionCounts : Map::of;
        this.tunnelRunnerLookup = tunnelRunnerLookup != null ? tunnelRunnerLookup : id -> null;
        try {
            this.server = HttpServer.create(new InetSocketAddress(config.port()), 0);
        } catch (BindException e) {
            if (config.killProc() && System.getProperty("os.name").toLowerCase().contains("win")) {
                killProcessUsingPort(config.port());
                this.server = HttpServer.create(new InetSocketAddress(config.port()), 0);
            } else {
                throw e;
            }
        }
        this.server.createContext("/", this::handleHttp);
    }

    public void start() {
        server.start();
    }

    public void stop(int delay) {
        server.stop(delay);
    }

    private void handleHttp(HttpExchange httpExchange) throws IOException {
        try {
            handleHttpInner(httpExchange);
        } catch (RuntimeException e) {
            // An uncaught exception here previously left the client with an empty
            // response (com.sun.net.httpserver's default behavior on a handler
            // throwing) rather than any indication something went wrong -- log it
            // and return a proper 500 instead, so failures are visible/debuggable.
            log.error("Unhandled exception handling {}: {}", httpExchange.getRequestURI(), e.getMessage(), e);
            var body = ("Internal error: " + e.getMessage()).getBytes(StandardCharsets.UTF_8);
            httpExchange.getResponseHeaders().set("Content-Type", "text/plain");
            httpExchange.sendResponseHeaders(500, body.length);
            try (var out = httpExchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private void handleHttpInner(HttpExchange httpExchange) throws IOException {
        if (httpExchange.getRequestURI().getPath().startsWith("/favicon.ico")) {
            httpExchange.sendResponseHeaders(404, 0);
            return;
        }
        log.info("Handling request. {}", httpExchange.getRequestURI());
        if (!httpExchange.getRequestMethod().equalsIgnoreCase("GET")) {
            httpExchange.sendResponseHeaders(405, 0);
            return;
        }
        var path = httpExchange.getRequestURI().getPath();

        if (path.startsWith("/q/")) {
            handleQuickLookup(httpExchange, path);
            return;
        }

        if (path.equals("/services")) {
            handleServicesJson(httpExchange);
            return;
        }

        // Bootstrap (webjar) and our own static/style.css, served straight from the
        // classpath rather than routed through Pebble -- see #serveClasspathResource.
        if (path.startsWith("/webjars/")) {
            serveClasspathResource(httpExchange, "META-INF/resources" + path);
            return;
        }
        if (path.startsWith("/static/")) {
            serveClasspathResource(httpExchange, path.substring(1));
            return;
        }

        httpExchange.getResponseHeaders().set("Content-Type", "text/html");
        var query = decodeQuery(httpExchange.getRequestURI().getQuery());

        int responseCode = 200;

        var backToList = "1".equals(query.get("edit")) ? "/list?edit=1" : "/list";

        var response = switch (path) {
            case "/list" -> listTunnels(httpExchange, "1".equals(query.get("edit")));
            case "/" -> index();
            case "/addService" -> handleAddService(query);
            case "/startTunnel" -> {
                httpExchange.getResponseHeaders().set("Refresh", "0 url=" + backToList);
                httpExchange.getResponseHeaders().set("Location", backToList);
                responseCode = 302;
                Main.startTunnel(query.get("id"));
                yield "OK";
            }
            case "/stopTunnel" -> {
                httpExchange.getResponseHeaders().set("Refresh", "0 url=" + backToList);
                httpExchange.getResponseHeaders().set("Location", backToList);
                responseCode = 302;
                Main.stopTunnel(query.get("id"));
                yield "OK";
            }
            case "/restartTunnel" -> {
                httpExchange.getResponseHeaders().set("Refresh", "0 url=" + backToList);
                httpExchange.getResponseHeaders().set("Location", backToList);
                responseCode = 302;
                Main.stopTunnel(query.get("id"));
                Main.startTunnel(query.get("id"));
                yield "OK";
            }
            case "/deleteTunnel" -> {
                httpExchange.getResponseHeaders().set("Refresh", "0 url=/list?edit=1");
                httpExchange.getResponseHeaders().set("Location", "/list?edit=1");
                responseCode = 302;
                // Edit mode: applies immediately (the tunnel actually stops) but stays
                // unpersisted until Save changes / Undo -- see Main#addTunnel(Tunnel, boolean).
                // A repeated id= (delete-all-contexts, see #deleteAllIcon) removes each in turn.
                decodeQueryValues(httpExchange.getRequestURI().getQuery(), "id").forEach(id -> Main.removeTunnel(id, false));
                yield "OK";
            }
            case "/editTunnel" -> handleEditTunnel(query);
            case "/migrateContext" -> handleMigrateContext(query);
            case "/saveChanges" -> {
                httpExchange.getResponseHeaders().set("Refresh", "0 url=/list");
                httpExchange.getResponseHeaders().set("Location", "/list");
                responseCode = 302;
                Main.saveChanges();
                yield "OK";
            }
            case "/discardChanges" -> {
                httpExchange.getResponseHeaders().set("Refresh", "0 url=/list");
                httpExchange.getResponseHeaders().set("Location", "/list");
                responseCode = 302;
                Main.reloadConfig();
                yield "OK";
            }
            case "/intellij" -> {
                httpExchange.getResponseHeaders().set("Content-Type", "text/plain");

                var id = query.get("id");
                var host = getHost(httpExchange);
                yield intellij(id, host);
            }
            default -> null;
        };
        if (response != null) {
            httpExchange.sendResponseHeaders(responseCode, response.getBytes().length);
            try (var out = httpExchange.getResponseBody()) {
                out.write(response.getBytes());
                out.flush();
            }
        } else {
            httpExchange.sendResponseHeaders(404, 0);
            log.info("Not found");
        }
    }

    /**
     * Handles {@code GET /q/<service>} and {@code GET /q/<service>/<context>}. Thin
     * HTTP wrapper around {@link #bareTargetName(String)} / {@link #lookupService}/
     * {@link #lookupServiceInContext} so the matching/formatting logic itself is
     * unit-testable without an {@code HttpServer}.
     */
    private void handleQuickLookup(HttpExchange httpExchange, String path) throws IOException {
        httpExchange.getResponseHeaders().set("Content-Type", "text/plain");

        // path is "/q/<service>" or "/q/<service>/<context>"
        var rest = path.substring("/q/".length());
        var parts = rest.split("/", 2);
        var service = decode(parts.length > 0 ? parts[0] : "");
        var context = parts.length > 1 ? decode(parts[1]) : null;

        String body;
        int code;
        if (service == null || service.isBlank()) {
            code = 404;
            body = "no match for \"\"";
        } else if (context == null) {
            var matches = lookupService(config, service);
            if (matches.isEmpty()) {
                code = 404;
                body = "no match for \"%s\"".formatted(service);
            } else {
                code = 200;
                body = matches.stream()
                        .map(t -> t.getContext() + "/" + t.getLocalPort())
                        .collect(Collectors.joining("\n"));
            }
        } else {
            var matches = lookupServiceInContext(config, service, context);
            if (matches.size() != 1) {
                code = 404;
                body = matches.isEmpty()
                        ? "no match for \"%s\" in context \"%s\"".formatted(service, context)
                        : "ambiguous match for \"%s\" in context \"%s\" (%d matches)".formatted(service, context, matches.size());
            } else {
                code = 200;
                body = String.valueOf(matches.get(0).getLocalPort());
            }
        }

        var bytes = body.getBytes(StandardCharsets.UTF_8);
        httpExchange.sendResponseHeaders(code, bytes.length);
        try (var out = httpExchange.getResponseBody()) {
            out.write(bytes);
            out.flush();
        }
    }

    /**
     * Whether a {@code mode} form parameter (from the add-service or edit-tunnel
     * forms' "Connection" dropdown, whose SOCKS option has {@code value="socks"})
     * means "use SOCKS" as opposed to a direct port-forward. Single source of
     * truth for both forms' submit handlers -- factored out, and package-visible
     * for unit tests, after a bug where the edit form's submit handler checked
     * for {@code "service"} instead of the dropdown's actual {@code "socks"}
     * value, silently applying direct port-forward every time SOCKS was chosen.
     */
    static boolean isSocksModeParam(String modeParam) {
        return "socks".equalsIgnoreCase(modeParam);
    }

    /**
     * Strips a {@code kind/} prefix (e.g. {@code service/}, {@code deployment/}) off a
     * tunnel's {@code target}, e.g. {@code "service/play-api-public"} -&gt;
     * {@code "play-api-public"}. Pure, package-visible for unit tests.
     */
    static String bareTargetName(String target) {
        if (target == null) return "";
        var idx = target.indexOf('/');
        return idx >= 0 ? target.substring(idx + 1) : target;
    }

    /**
     * Case-insensitive substring match of {@code query} against each tunnel's bare
     * target name, across all groups/contexts. Ordered by {@code config.groups()}
     * order (tunnels whose group matches a known group first, in that group order),
     * then any remainder alphabetically by context. Pure, package-visible for unit
     * tests (see step 4.4 of BIG-REFACTOR.md).
     */
    static List<Tunnel> lookupService(Configuration config, String query) {
        var q = query.toLowerCase();
        var groups = config.groups();
        Comparator<Tunnel> byGroupThenContext = Comparator
                .<Tunnel>comparingInt(t -> {
                    var idx = groups.indexOf(t.getGroup());
                    return idx >= 0 ? idx : groups.size();
                })
                .thenComparing(Tunnel::getContext);

        return config.portForwards().stream()
                .filter(t -> bareTargetName(t.getTarget()).toLowerCase().contains(q))
                .sorted(byGroupThenContext)
                .toList();
    }

    /**
     * As {@link #lookupService} but additionally filtered to tunnels whose
     * {@link Tunnel#getContext()} exactly equals {@code context}. Pure,
     * package-visible for unit tests.
     */
    static List<Tunnel> lookupServiceInContext(Configuration config, String query, String context) {
        return lookupService(config, query).stream()
                .filter(t -> t.getContext().equals(context))
                .toList();
    }

    /**
     * The three real kubectl contexts this app targets (BIG-REFACTOR.md's stated
     * setup). Used by the add-service flow (step 3.3) to create the prod/test/dev
     * triad of tunnels in one action. Hardcoded per BIG-REFACTOR.md's task note
     * rather than derived from {@code new-forwards.yaml} (an untracked file not
     * guaranteed present in every checkout).
     */
    static final List<String> KNOWN_CONTEXTS = List.of("bhg-prod", "bhg-test", "bhg-dev");
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Renders {@code src/main/resources/templates/&lt;name&gt;} (a {@code .peb} Pebble
     * template) with the given model. Built once and reused -- Pebble templates are
     * compiled/cached internally after first use.
     */
    private static final PebbleEngine PEBBLE = new PebbleEngine.Builder()
            .loader(new ClasspathLoader() {{
                setPrefix("templates/");
            }})
            // Pebble's default "newline trimming" swallows the newline right after a
            // {{ }} expression -- harmless for the HTML pages (browsers collapse
            // whitespace) but corrupts intellijDatasource.peb's exact-format output
            // (IntelliJ's paste-import is whitespace-sensitive), so it's off globally.
            .newLineTrimming(false)
            .build();

    private static String render(String templateName, Map<String, Object> model) {
        try {
            var template = PEBBLE.getTemplate(templateName);
            var writer = new StringWriter();
            template.evaluate(writer, model);
            return writer.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to render template " + templateName + ": " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> baseModel(String title) {
        var model = new HashMap<String, Object>();
        model.put("title", title);
        model.put("version", Main.VERSION.getVersion());
        return model;
    }

    /**
     * BIG-REFACTOR.md step 3.1: {@code GET /services?context=&namespace=} — returns
     * the candidate {@link K8sService} list (real cluster services not already
     * configured as tunnels for that context) as JSON. Thin wrapper around
     * {@link #candidateServices} for programmatic/scripting use; the actual
     * add-service form (3.3) calls {@link KubectlServiceLister} + {@link #candidateServices}
     * directly rather than round-tripping through this endpoint.
     */
    private void handleServicesJson(HttpExchange httpExchange) throws IOException {
        httpExchange.getResponseHeaders().set("Content-Type", "application/json");
        var query = decodeQuery(httpExchange.getRequestURI().getQuery());
        var context = query.get("context");
        var namespace = Optional.ofNullable(query.get("namespace")).orElse("default");

        byte[] bytes;
        int code;
        if (context == null || context.isBlank()) {
            code = 400;
            bytes = "{\"error\":\"missing required query parameter 'context'\"}".getBytes(StandardCharsets.UTF_8);
        } else {
            var all = KubectlServiceLister.listServices(context, namespace);
            var candidates = candidateServices(config, context, all);
            code = 200;
            try {
                bytes = JSON.writeValueAsBytes(candidates);
            } catch (Exception e) {
                code = 500;
                bytes = "{\"error\":\"failed to serialize services\"}".getBytes(StandardCharsets.UTF_8);
            }
        }
        httpExchange.sendResponseHeaders(code, bytes.length);
        try (var out = httpExchange.getResponseBody()) {
            out.write(bytes);
            out.flush();
        }
    }

    /**
     * BIG-REFACTOR.md step 3.1's dedup heuristic: a {@link K8sService} is considered
     * "already configured" for a context if some tunnel in {@code config.portForwards()}
     * has that exact {@code context} and a {@code target} of {@code service/<name>}.
     * Pure, package-visible for unit tests.
     */
    static List<K8sService> candidateServices(Configuration config, String context, List<K8sService> discovered) {
        var configuredTargets = config.portForwards().stream()
                .filter(t -> context.equals(t.getContext()))
                .map(t -> bareTargetName(t.getTarget()).toLowerCase())
                .collect(Collectors.toSet());

        return discovered.stream()
                .filter(svc -> svc.name() != null && !configuredTargets.contains(svc.name().toLowerCase()))
                .toList();
    }

    /**
     * Delegates to {@link PortAllocator#nextServiceIndex} (step 3.2). Kept as a
     * thin wrapper (rather than inlining calls to {@code PortAllocator} directly
     * at every call site) since it's already covered by existing tests here.
     */
    static int nextServiceIndex(Configuration config) {
        return PortAllocator.nextServiceIndex(config);
    }

    /**
     * Delegates to {@link PortAllocator#portsFor} (step 3.2), adapting its
     * {@code ServicePorts} record to the {@code {prod, test, dev}} array shape
     * this class's callers expect.
     */
    static int[] portsForServiceIndex(int serviceIndex) {
        var ports = PortAllocator.portsFor(serviceIndex);
        return new int[]{ports.prod(), ports.test(), ports.dev()};
    }

    /**
     * BIG-REFACTOR.md step 3.3: {@code GET /addService?context=&namespace=} shows a
     * server-rendered form (no client-side JS, consistent with the rest of this
     * app) with a dropdown of candidate services (obtained by calling
     * {@link KubectlServiceLister} + {@link #candidateServices} directly, not via
     * the {@code /services} JSON endpoint) and a {@code type} selector. On submit
     * ({@code &submit=1&service=<name>:<port>&namespace=&type=}, GET-with-query per
     * this app's existing convention) creates three {@code mode: service} tunnels
     * (bhg-prod/bhg-test/bhg-dev) via {@link Main#addTunnel(Tunnel)} and shows a
     * confirmation page.
     */
    /**
     * "Edit tunnels" mode's spanner icon: {@code GET /editTunnel?id=} shows a
     * form (no client-side JS, per this app's existing convention) prefilled
     * with the tunnel's current editable fields -- target, namespace, local/
     * remote port, type, group, startOnStartup. Context, mode/dependsOn,
     * database, and SOCKS credentials are left as-is (not exposed in this
     * form) since they're either identity-defining or SOCKS-infrastructure
     * concerns better left to the config file for now. On submit ({@code
     * &submit=1&...}), since a {@link Tunnel}'s id is derived from its
     * content (group+context+target+namespace+localPort+destinationPort),
     * "editing" is really remove-old+add-new under the hood -- both staged
     * (persisted false) so the change takes effect immediately (the tunnel
     * actually restarts on its new settings) but isn't written to disk until
     * "Save changes"/"Undo".
     */
    /**
     * Per-context "Migrate to SOCKS" action (BIG-REFACTOR.md step 2.4's webUI
     * counterpart to the {@code --migrate-to-socks} CLI flag): {@code GET
     * /migrateContext?context=&edit=1} converts every {@code mode: PORT_FORWARD}
     * tunnel in that context to {@code mode: SERVICE} (creating the {@code mode:
     * SOCKS} cluster-tunnel entry first if the context doesn't have one yet),
     * staged the same way as other edit-mode actions (applies live, left
     * unpersisted until "Save changes"/"Undo"). No per-service filter here --
     * that's what the CLI's {@code --migrate-service} is for; this is the coarse
     * "just migrate everything in this context" action. Not linked from the
     * toolbar (per user preference -- the CLI and this hidden endpoint are enough
     * for a bulk-migration action used rarely); reachable directly by URL.
     */
    private String handleMigrateContext(Map<String, String> query) {
        var context = query.get("context");
        var plan = MigrationHelper.plan(config, Set.of(context), List.of());

        for (var t : plan.newClusterTunnels()) {
            Main.addTunnel(t, false);
        }
        for (var c : plan.toConvert()) {
            Main.removeTunnel(c.before().getId(), false);
            Main.addTunnel(c.after(), false);
        }

        var model = baseModel("Migrate " + context + " - Tunnellen");
        model.put("heading", "Migrate " + context + " to SOCKS");
        var bodyHtml = plan.isEmpty()
                ? "<p>Nothing to migrate -- every eligible tunnel in <strong>" + context + "</strong> is already on SOCKS.</p>"
                : "<p>Not yet saved -- click <strong>Save changes</strong> in the toolbar to persist, or <strong>Undo</strong> to discard.</p>"
                + "<pre>" + MigrationHelper.describe(plan) + "</pre>"
                + (plan.newClusterTunnels().isEmpty() ? "" : "<p><strong>Note:</strong> a new cluster SOCKS tunnel "
                + "was staged for this context -- it won't actually start until tunnellen is restarted "
                + "(cluster tunnels are only brought up at startup).</p>");
        model.put("bodyHtml", bodyHtml);
        model.put("backHref", "/list?edit=1");
        model.put("backLabel", "Back to tunnel list");
        return render("message.peb", model);
    }

    private String handleEditTunnel(Map<String, String> query) {
        var id = query.get("id");
        var existing = config.portForwards().stream().filter(t -> t.getId().equals(id)).findFirst();
        if (existing.isEmpty()) {
            var model = baseModel("Edit tunnel - Tunnellen");
            model.put("notFound", true);
            return render("editTunnel.peb", model);
        }
        var tun = existing.get();

        if ("1".equals(query.get("submit"))) {
            var target = Optional.ofNullable(query.get("target")).filter(s -> !s.isBlank()).orElse(tun.getTarget());
            var namespace = Optional.ofNullable(query.get("namespace")).filter(s -> !s.isBlank()).orElse(tun.getNamespace());
            var localPort = Optional.ofNullable(query.get("localPort")).map(Integer::parseInt).orElse(tun.getLocalPort());
            var remotePort = Optional.ofNullable(query.get("remotePort")).filter(s -> !s.isBlank()).orElse(tun.getDestinationPort());
            var typeParam = Optional.ofNullable(query.get("type")).orElse("");
            var type = "database".equalsIgnoreCase(typeParam) ? Tunnel.Type.DATABASE
                    : "http".equalsIgnoreCase(typeParam) ? Tunnel.Type.HTTP
                    : tun.getType().orElse(null);
            var group = Optional.ofNullable(query.get("group")).filter(s -> !s.isBlank()).orElse(tun.getGroup());
            var startOnStartup = "1".equals(query.get("startOnStartup"));

            // mode:SOCKS entries are cluster tunnel definitions, not editable to become
            // a service via this form (no "mode" field is rendered for them below, so
            // this branch preserves whatever they already had). Everything else can
            // switch between SOCKS (rides its own context's cluster tunnel, dependsOn
            // set to that context -- same convention as the add-service flow) and a
            // direct port-forward (dependsOn cleared).
            Tunnel.Mode mode;
            String dependsOn;
            if (tun.getMode() == Tunnel.Mode.SOCKS) {
                mode = tun.getMode();
                dependsOn = tun.getDependsOn().orElse(null);
            } else if (isSocksModeParam(query.get("mode"))) {
                mode = Tunnel.Mode.SERVICE;
                dependsOn = tun.getContext();
            } else {
                mode = Tunnel.Mode.PORT_FORWARD;
                dependsOn = null;
            }

            var updated = new Tunnel(group, tun.getContext(), target, namespace, localPort, remotePort, startOnStartup,
                    type, tun.getDatabase(), mode, dependsOn,
                    tun.getSocksUsername().orElse(null), tun.getSocksPassword().orElse(null));

            Main.removeTunnel(id, false);
            Main.addTunnel(updated, false);

            var model = baseModel("Tunnel updated - Tunnellen");
            model.put("heading", "Tunnel updated");
            model.put("bodyHtml", "<p>Not yet saved -- click <strong>Save changes</strong> in the toolbar to persist, or <strong>Undo</strong> to discard.</p>");
            model.put("backHref", "/list?edit=1");
            model.put("backLabel", "Back to tunnel list");
            return render("message.peb", model);
        }

        var typeOptions = Arrays.stream(Tunnel.Type.values())
                .map(t -> Map.<String, Object>of(
                        "value", t.name().toLowerCase(Locale.ROOT),
                        "selected", tun.getType().filter(t::equals).isPresent()))
                .toList();

        var model = baseModel("Edit tunnel - Tunnellen");
        model.put("notFound", false);
        model.put("id", id);
        model.put("context", tun.getContext());
        model.put("target", tun.getTarget());
        model.put("namespace", tun.getNamespace());
        model.put("localPort", tun.getLocalPort());
        model.put("remotePort", tun.getDestinationPort());
        model.put("typeOptions", typeOptions);
        model.put("group", tun.getGroup());
        model.put("startOnStartup", tun.isStartOnStartup());
        model.put("isSocksClusterTunnel", tun.getMode() == Tunnel.Mode.SOCKS);
        model.put("isService", tun.getMode() == Tunnel.Mode.SERVICE);
        return render("editTunnel.peb", model);
    }

    private String handleAddService(Map<String, String> query) {
        var context = Optional.ofNullable(query.get("context")).filter(s -> !s.isBlank()).orElse(KNOWN_CONTEXTS.get(2));
        var namespace = Optional.ofNullable(query.get("namespace")).filter(s -> !s.isBlank()).orElse("default");

        if ("1".equals(query.get("submit"))) {
            return handleAddServiceSubmit(query, namespace);
        }

        return addServiceForm(context, namespace, null);
    }

    private String addServiceForm(String context, String namespace, String message) {
        var services = KubectlServiceLister.listServices(context, namespace);
        var candidates = candidateServices(config, context, services);

        var contexts = KNOWN_CONTEXTS.stream()
                .map(ctx -> Map.<String, Object>of("value", ctx, "selected", ctx.equals(context)))
                .toList();

        var serviceOptions = candidates.stream().<Map<String, Object>>mapMulti((svc, consumer) -> {
            var ports = svc.ports() != null && !svc.ports().isEmpty() ? svc.ports() : List.of(new K8sServicePort(null, 0, null, null));
            for (var port : ports) {
                var value = svc.name() + ":" + port.port();
                var label = svc.name() + " (port " + port.port() + (port.name() != null ? " / " + port.name() : "") + ")";
                consumer.accept(Map.of("value", value, "label", label));
            }
        }).toList();

        // Preview of the ports the prod/test/dev triad will land on if a service is
        // added right now -- independent of which service ends up chosen (the next
        // free SSS block is the same regardless), so it's safe to compute and show
        // up front rather than only after submitting.
        var previewIndex = nextServiceIndex(config);
        var previewPorts = portsForServiceIndex(previewIndex);
        var portPreview = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < KNOWN_CONTEXTS.size(); i++) {
            portPreview.add(Map.of("context", KNOWN_CONTEXTS.get(i), "port", previewPorts[i]));
        }

        var model = baseModel("Add service - Tunnellen");
        model.put("message", message);
        model.put("contexts", contexts);
        model.put("context", context);
        model.put("namespace", namespace);
        model.put("candidates", candidates);
        model.put("serviceOptions", serviceOptions);
        model.put("knownContexts", KNOWN_CONTEXTS);
        model.put("portPreview", portPreview);
        return render("addService.peb", model);
    }

    private String handleAddServiceSubmit(Map<String, String> query, String namespace) {
        var serviceAndPort = query.get("service");
        if (serviceAndPort == null || !serviceAndPort.contains(":")) {
            return addServiceForm(Optional.ofNullable(query.get("context")).orElse(KNOWN_CONTEXTS.get(2)), namespace,
                    "Please choose a service.");
        }
        var parts = serviceAndPort.split(":", 2);
        var serviceName = parts[0];
        var remotePort = parts[1];
        var typeParam = Optional.ofNullable(query.get("type")).orElse("http");
        var type = "database".equalsIgnoreCase(typeParam) ? Tunnel.Type.DATABASE : Tunnel.Type.HTTP;

        var target = "service/" + serviceName;
        var index = nextServiceIndex(config);
        var ports = portsForServiceIndex(index);
        var groups = List.of("prod", "test", "dev");
        var useSocks = isSocksModeParam(Optional.ofNullable(query.get("mode")).orElse("socks"));

        var created = new ArrayList<Tunnel>();
        for (int i = 0; i < KNOWN_CONTEXTS.size(); i++) {
            var ctx = KNOWN_CONTEXTS.get(i);
            var group = groups.get(i);
            var tun = useSocks
                    ? new Tunnel(group, ctx, target, namespace, ports[i], remotePort, true, type, null, Tunnel.Mode.SERVICE, ctx)
                    : new Tunnel(group, ctx, target, namespace, ports[i], remotePort, true, type, null);
            // Persists to YAML and starts the tunnel; see step 3.4's Main.addTunnel(Tunnel).
            Main.addTunnel(tun);
            created.add(tun);
        }

        var listHtml = new StringBuilder("<ul>\n");
        for (var t : created) {
            listHtml.append("<li>").append(t.getGroup()).append(" / ").append(t.getContext())
                    .append(" &mdash; local port ").append(t.getLocalPort()).append("</li>\n");
        }
        listHtml.append("</ul>\n");
        var bodyHtml = "<p>Created tunnels for <strong>" + target + "</strong> (service index " + index
                + ", remote port " + remotePort + "):</p>\n" + listHtml;

        var model = baseModel("Service added - Tunnellen");
        model.put("heading", "Service added");
        model.put("bodyHtml", bodyHtml);
        model.put("backHref", "/list");
        model.put("backLabel", "Back to tunnel list");
        return render("message.peb", model);
    }

    /**
     * Single-valued query param map: a repeated key (e.g. {@code ?id=A&id=B})
     * keeps only the last occurrence -- {@link Collectors#toMap(Function,
     * Function)} without a merge function throws on a duplicate key otherwise,
     * which used to take down the whole request (a plain, uncaught exception
     * from inside {@code decodeQuery}, called unconditionally for nearly every
     * route) rather than just misbehaving for that one param. Routes that
     * legitimately need every value for a repeated key (delete-all-contexts,
     * see {@link #deleteAllIcon}) use {@link #decodeQueryValues} instead.
     */
    private Map<String, String> decodeQuery(String query) {
        return query != null ? Pattern.compile("&")
                .splitAsStream(query)
                .map(s -> Arrays.copyOf(s.split("=", 2), 2))
                .map(o -> Map.entry(decode(o[0]), decode(o[1])))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (first, last) -> last)) : Map.of();
    }

    /** Every value for {@code key} in {@code query}, in order, for a repeated query param. */
    private List<String> decodeQueryValues(String query, String key) {
        if (query == null) return List.of();
        return Pattern.compile("&")
                .splitAsStream(query)
                .map(s -> Arrays.copyOf(s.split("=", 2), 2))
                .filter(o -> key.equals(decode(o[0])))
                .map(o -> decode(o[1]))
                .toList();
    }

    private String decode(final String encoded) {
        return Optional.ofNullable(encoded)
                .map(e -> URLDecoder.decode(e, StandardCharsets.UTF_8))
                .orElse(null);
    }

    private String listTunnels(HttpExchange httpExchange, boolean editMode) {
        if (config.refreshInterval().toSeconds() > 0) {
            httpExchange.getResponseHeaders().set("Refresh", "%d url=/list%s".formatted(config.refreshInterval().toSeconds(), editMode ? "?edit=1" : ""));
        }

        var host = getHost(httpExchange);

        // Columns: one per configured group (e.g. prod/test/dev), plus a catch-all
        // "Other" column for any tunnel whose group isn't one of config.groups().
        var columns = new ArrayList<>(config.groups());
        var hasOtherGroup = config.portForwards().stream().anyMatch(t -> config.groups().stream().noneMatch(g -> g.equals(t.getGroup())));
        if (hasOtherGroup) columns.add("Other");

        // Group by target (service), ignoring group/context - group is now a column.
        // mode:SOCKS entries are cluster tunnel definitions, not services -- they have
        // no target (null), which would otherwise NPE grouping into a TreeMap (which
        // rejects null keys), so they're excluded from this listing entirely.
        var byTarget = config.portForwards().stream()
                .filter(t -> t.getMode() != Tunnel.Mode.SOCKS)
                .collect(Collectors.groupingBy(Tunnel::getTarget, TreeMap::new, Collectors.toList()));

        // Split into services present in 2+ environments (the main grid) vs. services
        // that only exist in a single environment (listed separately below), per the
        // user's "grouped by best effort" request.
        var gridServices = new TreeMap<String, List<Tunnel>>();
        var soloServices = new TreeMap<String, List<Tunnel>>();
        byTarget.forEach((target, tunnels) -> {
            var distinctColumns = tunnels.stream().map(this::columnFor).distinct().count();
            (distinctColumns >= 2 ? gridServices : soloServices).put(target, tunnels);
        });

        var clusterStatus = safeClusterTunnelStatus();
        var connectionCounts = safeClusterConnectionCounts();
        var clusterBadges = clusterStatus.keySet().stream()
                .sorted()
                .map(ctx -> Map.<String, Object>of("context", ctx, "alive", clusterStatus.get(ctx),
                        "connections", connectionCounts.getOrDefault(ctx, 0)))
                .toList();

        var sections = new ArrayList<Map<String, Object>>();
        sections.add(sectionModel("Services", columns, gridServices, host, editMode));
        if (!soloServices.isEmpty()) {
            sections.add(sectionModel("Other services", columns, soloServices, host, editMode));
        }

        var model = baseModel("Tunnels - Tunnellen");
        model.put("editMode", editMode);
        model.put("clusterBadges", clusterBadges);
        model.put("sections", sections);
        return render("list.peb", model);
    }

    /**
     * Which column (configured group, or "Other" if the tunnel's group isn't one
     * of {@code config.groups()}) a tunnel belongs to.
     */
    private String columnFor(Tunnel t) {
        return config.groups().contains(t.getGroup()) ? t.getGroup() : "Other";
    }

    /**
     * One collapsible (4.2) section's view model for {@code list.peb}: a grid with
     * one column per environment (group) and one row per service, for the given
     * subset of {@code byTarget}. Per-cell HTML ({@code targetHtml}/{@code cells})
     * is still built via the existing {@link #target}/{@link #cell} helpers rather
     * than in the template -- see the class-level note on this hybrid approach.
     */
    private Map<String, Object> sectionModel(String title, List<String> columns, Map<String, List<Tunnel>> byTarget, String host, boolean editMode) {
        var rows = new ArrayList<Map<String, Object>>();
        byTarget.forEach((target, tunnels) -> {
            var type = tunnels.stream().map(Tunnel::getType).filter(Optional::isPresent).map(Optional::get).findFirst();
            var cells = columns.stream()
                    .map(column -> tunnels.stream().filter(t -> column.equals(columnFor(t))).toList())
                    .map(cellTunnels -> cell(cellTunnels, host, editMode))
                    .toList();
            rows.add(Map.of("targetHtml", target(target, type, tunnels, editMode), "cells", cells));
        });
        var displayColumns = columns.stream().map(Web::capitalize).toList();
        return Map.of("title", title, "columns", displayColumns, "rows", rows);
    }

    private Map<String, Boolean> safeClusterTunnelStatus() {
        try {
            var status = clusterTunnelStatus.get();
            return status != null ? status : Map.of();
        } catch (Exception e) {
            log.warn("Failed to obtain cluster tunnel status: {}", e.getMessage());
            return Map.of();
        }
    }

    private Map<String, Integer> safeClusterConnectionCounts() {
        try {
            var counts = clusterConnectionCounts.get();
            return counts != null ? counts : Map.of();
        } catch (Exception e) {
            log.warn("Failed to obtain cluster connection counts: {}", e.getMessage());
            return Map.of();
        }
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    private String cell(List<Tunnel> tunnels, String host, boolean editMode) {
        if (tunnels.isEmpty()) {
            return "<span class=\"notimportant\">&mdash;</span>";
        }
        return tunnels.stream().map(t -> tunnel(t, host, editMode)).collect(Collectors.joining("<hr class=\"cellsep\">"));
    }

    private static String getHost(HttpExchange httpExchange) {
        return Optional.ofNullable(httpExchange.getRequestHeaders().getFirst("Host")).map(h -> h.split(":")[0]).orElse("127.0.0.1");
    }

    private String tunnel(Tunnel tun, String host, boolean editMode) {
        // Only a SocksServiceTunnel tracks active connections (a direct kubectl
        // port-forward's own connection multiplexing isn't visible to us); light up
        // the row when it's actually relaying something right now.
        var runner = tunnelRunnerLookup.apply(tun.getId());
        var active = runner instanceof SocksServiceTunnel socksServiceTunnel && socksServiceTunnel.getActiveConnectionCount() > 0;

        return """
                <div class="cellrow%s">
                <span class="notimportant">%s:%d</span>
                <span class="cellicons">%s%s</span>
                </div>
                """.formatted(
                active ? " active-connection" : "",
                tun.getContext(),
                tun.getLocalPort(),
                actionIcons(tun, host),
                editMode ? editIcons(tun) : ""
        );
    }

    /**
     * "Edit tunnels" mode's spanner (edit) and trashcan (delete) icons per tunnel,
     * shown alongside the existing start/stop/restart icons only while {@code
     * editMode} is on. Delete asks for confirmation via the browser's native
     * {@code confirm()} -- the only bit of inline JS in this otherwise JS-free app,
     * scoped to this one destructive action.
     */
    private String editIcons(Tunnel tun) {
        return """
                <a href="/editTunnel?id=%s" class="iconlink" title="Edit tunnel"><i class="bi bi-pencil-square"></i></a>
                <a href="/deleteTunnel?id=%s" class="iconlink deletebtn" title="Delete tunnel" onclick="return confirm('Delete this tunnel?');"><i class="bi bi-trash-fill"></i></a>
                """.formatted(tun.getId(), tun.getId());
    }

    /**
     * Renders the leftmost "Service" cell: the type icon + target name, plus (in
     * edit mode) a delete-all-contexts icon -- a service is really 2-3 separate
     * {@link Tunnel} entries (one per environment), so deleting "the service"
     * means deleting all of them in one action rather than hunting down each
     * environment's own trashcan icon individually.
     */
    private String target(String target, Optional<Tunnel.Type> type, List<Tunnel> tunnels, boolean editMode) {
        var parts = target.split("\\/", 2);
        var name = parts.length == 2 ? "<span class=\"targettype\">" + parts[0] + "</span>/<span class=\"targetname\">" + parts[1] + "</span>" : parts[0];
        var icon = type.filter(Tunnel.Type.DATABASE::equals).map(t -> "<span class=\"typeicon\" title=\"Database\"><i class=\"bi bi-database\"></i></span> ")
                .or(() -> type.filter(Tunnel.Type.HTTP::equals).map(t -> "<span class=\"typeicon\" title=\"HTTP\"><i class=\"bi bi-globe\"></i></span> "))
                .orElse("");
        if (!editMode) return icon + name;
        return "<span class=\"cellrow\"><span>" + icon + name + "</span>" + deleteAllIcon(tunnels) + "</span>";
    }

    /**
     * Delete-all-contexts icon for the Service column: one {@code /deleteTunnel}
     * link carrying every tunnel's id for this service as repeated {@code id=}
     * query params (e.g. {@code /deleteTunnel?id=A&id=B&id=C&edit=1}) -- the
     * {@code /deleteTunnel} route handler collects all of them via {@link
     * #decodeQueryValues}, since the single-valued {@link #decodeQuery} keeps
     * only the last occurrence of a repeated key.
     */
    private String deleteAllIcon(List<Tunnel> tunnels) {
        var ids = tunnels.stream().map(Tunnel::getId).map(id -> "id=" + id).collect(Collectors.joining("&"));
        return """
                 <a href="/deleteTunnel?%s&edit=1" class="iconlink deletebtn" title="Delete this service from all environments" onclick="return confirm('Delete this service from all environments?');"><i class="bi bi-trash-fill"></i></a>
                """.formatted(ids);
    }

    private String actionIcons(Tunnel tun, String host) {
        // The config Tunnel object's own isRunning()/getLastCheck() only reflect a
        // real "kubectl port-forward" per this exact object -- meaningless for a
        // mode:SERVICE tunnel, whose actual runtime is a separate SocksServiceTunnel
        // instance riding the shared cluster proxy. Look up whichever TunnelRunner is
        // actually live for this id; fall back to the Tunnel itself (correctly
        // "not started") if nothing's registered.
        TunnelRunner runner = Optional.<TunnelRunner>ofNullable(tunnelRunnerLookup.apply(tun.getId())).orElse(tun);

        var startStop = runner.isRunning() ? """
                <a href="/stopTunnel?id=%s" class="running" title="Stop tunnel"><i class="bi bi-stop-fill"></i></a>
                <a href="/restartTunnel?id=%s" class="running" title="Restart tunnel"><i class="bi bi-arrow-clockwise"></i></a>
                """.formatted(tun.getId(), tun.getId()) : """
                <a href="/startTunnel?id=%s" class="stopped" title="Start tunnel"><i class="bi bi-play-fill"></i></a>
                """.formatted(tun.getId());

        var db = tun.getType().filter(Tunnel.Type.DATABASE::equals).map(t -> """
                <a href="/intellij?id=%s" target="_blank" class="iconlink" title="Generate IntelliJ Datasource"><i class="bi bi-database-down"></i></a>
                """.formatted(tun.getId())).orElse("");

        var http = tun.getType().filter(Tunnel.Type.HTTP::equals).map(t -> "<a href=\"http://%s:%d\" class=\"iconlink\" target=\"_blank\"><i class=\"bi bi-box-arrow-up-right\"></i></a>".formatted(host, tun.getLocalPort())).orElse("");


        return startStop + db + http;
    }

    private String index() {
        return render("index.peb", baseModel("Tunnellen"));
    }

    /**
     * Serves a file from the classpath (Bootstrap's webjar under {@code
     * META-INF/resources/webjars/...}, or our own {@code static/style.css}) with a
     * best-effort content type guessed from the extension and a day-long cache
     * header -- these are versioned/static assets, safe to cache aggressively.
     */
    private void serveClasspathResource(HttpExchange httpExchange, String classpathPath) throws IOException {
        try (var in = Web.class.getClassLoader().getResourceAsStream(classpathPath)) {
            if (in == null) {
                httpExchange.sendResponseHeaders(404, 0);
                return;
            }
            var bytes = in.readAllBytes();
            httpExchange.getResponseHeaders().set("Content-Type", guessContentType(classpathPath));
            httpExchange.getResponseHeaders().set("Cache-Control", "max-age=86400");
            httpExchange.sendResponseHeaders(200, bytes.length);
            try (var out = httpExchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private static String guessContentType(String path) {
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".css.map") || path.endsWith(".js.map") || path.endsWith(".json")) return "application/json";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".woff2")) return "font/woff2";
        if (path.endsWith(".woff")) return "font/woff";
        return "application/octet-stream";
    }


    private String intellij(String id, String host) {
        return config.portForwards().stream().filter(t -> t.getId().equals(id) && t.getType().isPresent() && t.getType().get() == Tunnel.Type.DATABASE && t.getDatabase() != null).findFirst().map(t -> {
            var db = t.getDatabase();
            return generateDatasource(t.getGroup(), db, host, t.getLocalPort());
        }).orElse("Not found");
    }

    static String generateDatasource(String env, Database db, String proxyHost, int localPort) {
        var name = db.name() + "-" + env;
        var uuid = UUID.randomUUID().toString();
        var jdbcUrl = "jdbc:%s://%s:%d/%s".formatted(db.kind().jdbcPrefix, proxyHost, localPort, db.name());

        var group = env.substring(0, 1).toUpperCase() + env.substring(1);

        // dbKind must be a plain Map, not the Kind enum instance itself: Kind's
        // fields are package-private, and Pebble resolves template attributes via
        // reflection at runtime, which -- unlike ordinary compiled Java code in this
        // same package -- can't see non-public members at all. Extracting them here
        // sidesteps that entirely.
        var dbKind = Map.<String, Object>of(
                "product", db.kind().product,
                "jdbcVersion", db.kind().jdbcVersion,
                "driverName", db.kind().driverName,
                "driverVersion", db.kind().driverVersion,
                "dbms", db.kind().dbms,
                "exactDriverVersion", db.kind().exactDriverVersion,
                "identifierQuoteString", db.kind().identifierQuoteString,
                "jdbcPrefix", db.kind().jdbcPrefix,
                "driverClass", db.kind().driverClass
        );

        var vars = Map.<String, Object>of(
                "name", name,
                "group", group,
                "uuid", uuid,
                "jdbcUrl", jdbcUrl,
                "username", db.username(),
                "dbKind", dbKind
        );

        return render("intellijDatasource.peb", vars);
    }

    private void killProcessUsingPort(int port) throws IOException, InterruptedException {
        var pid = getPidUsingPort(port);
        if(pid.isPresent()) {
            var killCommand = new String[]{"cmd.exe", "/c", "taskkill /F /PID " + pid.get()};
            Runtime.getRuntime().exec(killCommand).waitFor();
            log.warn("Killed previous process using port {}", port);
        }
    }

    private Optional<String> getPidUsingPort(int port) throws IOException, InterruptedException {
        var proc = new ProcessBuilder("cmd", "/c", "netstat -ano").start();
        try (var isr = new InputStreamReader(proc.getInputStream()); var in = new BufferedReader(isr)) {
            return in.lines().filter(l -> l.contains(":" + port)).map(l -> {
                var parts = l.trim().split("\\s+");
                return parts[parts.length - 1];
            }).findAny();
        } finally {
            proc.waitFor();
        }
    }

}
