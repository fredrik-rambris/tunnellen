package dev.rambris.tunnellen;


import ch.qos.logback.classic.Logger;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public class Main {
    private static final Logger log = (Logger) LoggerFactory.getLogger(Main.class);
    private static Configuration config;
    private static KeepAlive keepAlive;
    private static Web web;

    /**
     * One {@link ClusterSocksTunnel} per unique context that has at least
     * one {@code mode=SOCKS} {@link Tunnel} depending on it (BIG-REFACTOR.md
     * step 1.4), keyed by context name.
     */
    private static Map<String, ClusterSocksTunnel> clusterTunnels = new HashMap<>();

    /**
     * BIG-REFACTOR.md step 2.3a: the actual running {@link TunnelRunner} for
     * each config {@link Tunnel}'s id — either the {@code Tunnel} itself
     * (direct {@code kubectl port-forward}, {@code Mode.PORT_FORWARD}) or a
     * {@link SocksServiceTunnel} wrapping it ({@code Mode.SERVICE}). {@code
     * Mode.SOCKS} entries never appear here — they're cluster tunnel
     * definitions, tracked separately in {@link #clusterTunnels}.
     */
    private static Map<String, TunnelRunner> runningTunnels = new HashMap<>();

    private static final int CLUSTER_SOCKS_LOCAL_PORT_BASE = 19000;

    private static int DEFAULT_PORT = 3000;
    private static File CONFIG_FILE = new File("forwards.yaml");
    private static List<String> MIGRATE_CONTEXTS = null;
    private static List<String> MIGRATE_SERVICES = List.of();
    private static boolean MIGRATE_DRY_RUN = false;

    static Version VERSION = new Version();

    public static void main(String[] args) throws Exception {
        log.info("Starting tunnellen version {}", VERSION.getVersion());
        commandLine(args);

        if (MIGRATE_CONTEXTS != null) {
            runMigration();
            return;
        }

        config = ConfigurationRepository.loadConfig(CONFIG_FILE, DEFAULT_PORT);
        keepAlive = new KeepAlive(config.keepAliveInterval());

        web = new Web(config, Main::clusterTunnelStatus, Main::clusterConnectionCounts, Main::liveTunnelFor);
        web.start();

        startClusterSocksTunnels();

        // Mode.SOCKS entries are cluster tunnel definitions, already started
        // above by startClusterSocksTunnels() — not started here as regular
        // TunnelRunners (BIG-REFACTOR.md step 2.3a).
        config.portForwards().forEach(tun -> {
            if (tun.isStartOnStartup() && tun.getMode() != Tunnel.Mode.SOCKS) {
                var runner = startTunnelRunner(tun);
                registerRunner(tun.getId(), runner);
            }
        });

        log.info("Listening on http://127.0.0.1:{}/. Ctrl-C to stop.", config.port());

        keepAlive.start();

        FileWatcher.onFileChange(CONFIG_FILE.toPath(), Main::reloadConfig);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down");
            keepAlive.stop();
            log.info("Stopping server");
            web.stop(0);
            log.info("Stopping tunnels");
            runningTunnels.values().forEach(TunnelRunner::stop);
            log.info("Stopping cluster SOCKS tunnels");
            clusterTunnels.values().forEach(ClusterSocksTunnel::stop);
        }));

    }

    /**
     * BIG-REFACTOR.md step 1.4: bring up one {@link ClusterSocksTunnel} per
     * distinct {@code mode: socks} entry's context, *before* any dependent
     * tunnels are started, and register each with {@link #keepAlive} for
     * health monitoring/restart (step 2.2 builds on this registration for
     * dependency-aware restart semantics).
     */
    /**
     * BIG-REFACTOR.md step 4.3: supplies {@link Web} with each cluster
     * tunnel's up/down state (context -&gt; isAlive), read fresh on every
     * call since {@link #clusterTunnels} is reassigned on reload.
     */
    private static Map<String, Boolean> clusterTunnelStatus() {
        var status = new HashMap<String, Boolean>();
        clusterTunnels.forEach((context, tunnel) -> status.put(context, tunnel.isAlive()));
        return status;
    }

    /**
     * Supplies {@link Web} with each cluster's active-connection count (context
     * -&gt; number of client connections currently being relayed through that
     * cluster's SOCKS proxy), summed across every {@link SocksServiceTunnel}
     * currently registered in {@link #runningTunnels} that rides that cluster.
     */
    private static Map<String, Integer> clusterConnectionCounts() {
        var counts = new HashMap<String, Integer>();
        for (var runner : runningTunnels.values()) {
            if (runner instanceof SocksServiceTunnel socksServiceTunnel) {
                var context = socksServiceTunnel.getClusterTunnel().getContext();
                counts.merge(context, socksServiceTunnel.getActiveConnectionCount(), Integer::sum);
            }
        }
        return counts;
    }

    /**
     * Supplies {@link Web} with whichever {@link TunnelRunner} is actually live
     * for a config {@link Tunnel}'s id, so the webUI can show correct start/stop
     * status for {@code mode: SERVICE} tunnels (whose live runtime is a separate
     * {@link SocksServiceTunnel}, not the config {@code Tunnel} object itself).
     */
    private static TunnelRunner liveTunnelFor(String id) {
        return runningTunnels.get(id);
    }

    /**
     * BIG-REFACTOR.md step 2.3a: constructs and starts the right {@link
     * TunnelRunner} for a config {@link Tunnel} based on its {@link
     * Tunnel.Mode}, rather than always running a direct {@code kubectl
     * port-forward} via {@code tun.start()}:
     * <ul>
     *   <li>{@code SOCKS} — should never reach here; these entries are
     *       cluster tunnel definitions, started by {@link
     *       #startClusterSocksTunnels()} and filtered out of the regular
     *       startup/reload loops. Logs a warning and no-ops defensively.</li>
     *   <li>{@code SERVICE} — looks up the {@link ClusterSocksTunnel} for
     *       {@code tun.getDependsOn()} and, if it's up, constructs+starts a
     *       {@link SocksServiceTunnel} riding on it. If the cluster tunnel
     *       isn't running yet (e.g. added dynamically before its cluster
     *       tunnel exists), falls back to a direct {@code kubectl
     *       port-forward} via {@code tun.start()} and logs a warning — a
     *       known limitation (BIG-REFACTOR.md 2.3a) since nothing currently
     *       retries the SOCKS path once the cluster tunnel does come up;
     *       fixing that is a follow-up, not blocking for this step.</li>
     *   <li>{@code PORT_FORWARD} (default) — direct {@code tun.start()}, as
     *       today.</li>
     * </ul>
     */
    private static TunnelRunner startTunnelRunner(Tunnel tun) {
        if (tun.getMode() == Tunnel.Mode.SOCKS) {
            log.warn("Tunnel {} has mode=SOCKS but was passed to startTunnelRunner; " +
                    "SOCKS-mode entries are cluster tunnel definitions and should be filtered " +
                    "out before reaching here. Ignoring.", tun.getId());
            return null;
        }

        if (tun.getMode() == Tunnel.Mode.SERVICE) {
            var context = tun.getDependsOn().orElse(null);
            var clusterTunnel = context != null ? clusterTunnels.get(context) : null;
            if (clusterTunnel != null) {
                var socksTunnel = new SocksServiceTunnel(tun.getContext(), tun.getTarget(), tun.getNamespace(),
                        tun.getLocalPort(), tun.getDestinationPort(), clusterTunnel);
                socksTunnel.start();
                return socksTunnel;
            }
            log.warn("Tunnel {}:{} depends on cluster tunnel for context '{}' which is not " +
                    "running; falling back to a direct kubectl port-forward instead of SOCKS " +
                    "(known limitation, BIG-REFACTOR.md step 2.3a)", tun.getContext(), tun.getTarget(), context);
        }

        tun.start();
        return tun;
    }

    /**
     * Registers a freshly-started {@link TunnelRunner} in {@link #runningTunnels}
     * and, for everything except a {@link SocksServiceTunnel}, with {@link
     * #keepAlive} for periodic health-check/restart.
     *
     * <p>{@code SocksServiceTunnel}s are deliberately NOT keepalive-monitored: a
     * plain {@code kubectl port-forward} (direct {@code Mode.PORT_FORWARD}, or the
     * {@code Mode.SERVICE} fallback when its cluster tunnel isn't up) is a real OS
     * subprocess that can genuinely die (network blip, node eviction, etc.), so
     * periodic liveness checks and restart-on-death make sense there. A {@code
     * SocksServiceTunnel}'s {@code ServerSocket} has no external process behind it
     * to crash -- it only closes when {@code stop()} is called -- so its {@code
     * isAlive()} (an active TCP self-connect every keepalive cycle) never tests
     * anything meaningful and risks a spurious restart that would forcibly drop
     * every live client connection through it for no real reason. What actually
     * needs monitoring -- the single shared {@code kubectl port-forward} to the
     * cluster's SOCKS proxy -- is the {@link ClusterSocksTunnel}, already
     * keepalive-monitored separately in {@link #startClusterSocksTunnels()}; if
     * that dies and is restarted, existing/new relay attempts through any
     * SocksServiceTunnel simply succeed again once it's back, with nothing to
     * restart at the per-service level.
     */
    private static void registerRunner(String id, TunnelRunner runner) {
        runningTunnels.put(id, runner);
        if (!(runner instanceof SocksServiceTunnel)) {
            keepAlive.addTunnel(runner);
        }
    }

    /**
     * Resolves the pod-name suffix identifying this developer for cluster SOCKS
     * pods (BIG-REFACTOR.md 1.1): {@code Configuration.socksPodSuffix()} (the
     * {@code socksPodSuffix} config key) first, falling back to the {@code
     * user.name} system property, failing fast with a clear message if neither
     * is available -- resolved once here rather than per-{@code
     * ClusterSocksTunnel}, so the failure (if any) happens once at startup with
     * a message naming both ways to fix it.
     */
    static String resolveSocksPodSuffix(Configuration config) {
        return config.socksPodSuffix()
                .or(() -> Optional.ofNullable(System.getProperty("user.name")).filter(s -> !s.isBlank()))
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot determine who to name cluster SOCKS pods after -- neither 'socksPodSuffix' " +
                        "in the config file nor the 'user.name' system property is set. This is required " +
                        "to give each developer their own SOCKS proxy pod per cluster and avoid colliding " +
                        "with other developers' tunnellen instances. Set socksPodSuffix: <yourname> in " +
                        "forwards.yaml, or pass -Duser.name=<yourname>, and try again."));
    }

    private static void startClusterSocksTunnels() {
        var socksTunnels = config.portForwards().stream()
                .filter(t -> t.getMode() == Tunnel.Mode.SOCKS)
                .toList();

        if (socksTunnels.isEmpty()) {
            clusterTunnels = new HashMap<>();
            return;
        }

        var podSuffix = resolveSocksPodSuffix(config);

        var newClusterTunnels = new HashMap<String, ClusterSocksTunnel>();
        int autoAssigned = 0;
        for (var t : socksTunnels) {
            var context = t.getContext();
            if (newClusterTunnels.containsKey(context)) {
                log.warn("Duplicate mode:socks entry for context {}, ignoring", context);
                continue;
            }
            // Respect an explicit localPort on the mode:socks entry (e.g.
            // "localPort: 9990" in forwards.yaml) so cluster tunnel ports are
            // predictable/documentable; auto-assign from
            // CLUSTER_SOCKS_LOCAL_PORT_BASE only when unset (0, the default
            // per ConfigurationRepository's null-localPort handling).
            var localPort = t.getLocalPort() > 0 ? t.getLocalPort() : CLUSTER_SOCKS_LOCAL_PORT_BASE + autoAssigned;
            if (t.getLocalPort() <= 0) {
                autoAssigned++;
            }
            var clusterTunnel = t.getSocksUsername().isPresent() && t.getSocksPassword().isPresent()
                    ? new ClusterSocksTunnel(context, localPort, t.getSocksUsername().get(), t.getSocksPassword().get(), podSuffix)
                    : new ClusterSocksTunnel(context, localPort, ClusterSocksTunnel.generateCredential(), ClusterSocksTunnel.generateCredential(), podSuffix);
            log.info("Starting cluster SOCKS tunnel for context {} on local port {}", context, clusterTunnel.getLocalPort());
            clusterTunnel.start();
            keepAlive.addClusterTunnel(clusterTunnel);
            newClusterTunnels.put(context, clusterTunnel);
        }
        clusterTunnels = newClusterTunnels;
    }

    private static void commandLine(String[] args) throws ParseException {
        var parser = new DefaultParser();
        var options = new Options();

        var portOption = Option.builder()
                .option("p")
                .longOpt("port")
                .hasArg(true)
                .type(Integer.class)
                .build();

        var configFileOption = Option.builder()
                .option("c")
                .longOpt("config")
                .hasArg(true)
                .type(File.class)
                .build();

        // BIG-REFACTOR.md step 2.4: bulk-convert mode:port-forward tunnels to
        // mode:service/SOCKS. --migrate-to-socks takes a comma-separated list of
        // contexts, or "all" for every context that has at least one tunnel.
        // --migrate-service (repeatable/comma-separated) restricts the conversion
        // to services whose bare target name contains one of the given substrings;
        // omitted, every eligible tunnel in the selected context(s) is converted.
        // --migrate-dry-run prints the plan without writing anything.
        var migrateToSocksOption = Option.builder()
                .longOpt("migrate-to-socks")
                .hasArg(true)
                .build();

        var migrateServiceOption = Option.builder()
                .longOpt("migrate-service")
                .hasArg(true)
                .build();

        var migrateDryRunOption = Option.builder()
                .longOpt("migrate-dry-run")
                .hasArg(false)
                .build();

        options
                .addOption(portOption)
                .addOption(configFileOption)
                .addOption(migrateToSocksOption)
                .addOption(migrateServiceOption)
                .addOption(migrateDryRunOption);

        var commandLine = parser.parse(options, args);

        DEFAULT_PORT = commandLine.getParsedOptionValue(portOption, DEFAULT_PORT);
        CONFIG_FILE = commandLine.getParsedOptionValue(configFileOption, CONFIG_FILE);

        if (commandLine.hasOption(migrateToSocksOption)) {
            MIGRATE_CONTEXTS = Arrays.asList(commandLine.getOptionValue(migrateToSocksOption).split(","));
        }
        if (commandLine.hasOption(migrateServiceOption)) {
            MIGRATE_SERVICES = Arrays.asList(commandLine.getOptionValues(migrateServiceOption));
        }
        MIGRATE_DRY_RUN = commandLine.hasOption(migrateDryRunOption);
    }

    /**
     * BIG-REFACTOR.md step 2.4: one-shot CLI entry point for {@code
     * --migrate-to-socks}, invoked from {@link #main} instead of the normal
     * startup path -- loads the config, plans + prints the migration (via {@link
     * MigrationHelper}), applies and persists it unless {@code --migrate-dry-run}
     * was given, then exits without starting the server or any tunnels. Safe to
     * re-run: a context/service already fully migrated just produces an empty
     * (no-op) plan for that portion of the selection, reported plainly rather
     * than as an error.
     */
    private static void runMigration() throws IOException {
        config = ConfigurationRepository.loadConfig(CONFIG_FILE, DEFAULT_PORT);

        Set<String> contexts;
        if (MIGRATE_CONTEXTS.size() == 1 && "all".equalsIgnoreCase(MIGRATE_CONTEXTS.get(0))) {
            contexts = config.portForwards().stream().map(Tunnel::getContext).collect(java.util.stream.Collectors.toSet());
        } else {
            contexts = new java.util.HashSet<>(MIGRATE_CONTEXTS);
        }

        var plan = MigrationHelper.plan(config, contexts, MIGRATE_SERVICES);
        System.out.println((MIGRATE_DRY_RUN ? "[dry run] " : "") + "Migration plan for context(s) "
                + String.join(", ", contexts) + (MIGRATE_SERVICES.isEmpty() ? "" : " (filtered to services matching: "
                + String.join(", ", MIGRATE_SERVICES) + ")") + ":");
        System.out.println(MigrationHelper.describe(plan));

        if (MIGRATE_DRY_RUN || plan.isEmpty()) {
            return;
        }

        for (var t : plan.newClusterTunnels()) {
            config.portForwards().add(t);
        }
        for (var c : plan.toConvert()) {
            config.portForwards().remove(c.before());
            config.portForwards().add(c.after());
        }
        ConfigurationRepository.saveConfig(CONFIG_FILE, config);
        System.out.println("Wrote " + CONFIG_FILE + ". Restart tunnellen for the new cluster SOCKS tunnel(s) "
                + "(if any) to actually start -- they're only brought up at startup (BIG-REFACTOR.md step 1.4).");
    }


    /**
     * BIG-REFACTOR.md step 2.3a: stops and unregisters whatever {@link
     * TunnelRunner} is actually running for {@code id} (a plain {@link
     * Tunnel} or a {@link SocksServiceTunnel}), not necessarily the config
     * {@code Tunnel} object itself — see {@link #runningTunnels}.
     */
    static void stopTunnel(String id) {
        var runner = runningTunnels.remove(id);
        if (runner != null) {
            keepAlive.removeTunnel(runner);
            runner.stop();
        }
    }

    static void startTunnel(String id) {
        if (runningTunnels.containsKey(id)) {
            return;
        }
        config.portForwards().stream().filter(t -> t.getId().equals(id)).findFirst().ifPresent(tun -> {
            var runner = startTunnelRunner(tun);
            registerRunner(id, runner);
        });
    }

    static void addTunnel(Tunnel tun) {
        addTunnel(tun, true);
    }

    /**
     * @param persist whether to write {@link #CONFIG_FILE} after mutating. Passed
     *                {@code false} from {@link #reloadConfig()}'s own add/remove
     *                calls (those additions/removals came *from* the file we'd be
     *                writing back to, so re-saving there is redundant) and from the
     *                webUI's "edit tunnels" mode (BIG-REFACTOR.md follow-up): edits
     *                and deletes made while in edit mode apply immediately (the
     *                tunnel actually restarts on the new port etc.) but stay
     *                unpersisted until the toolbar's "Save changes" button calls
     *                {@link #saveChanges()} explicitly, or "Undo" calls {@link
     *                #reloadConfig()} to discard them and revert to disk.
     */
    static void addTunnel(Tunnel tun, boolean persist) {
        if (config.portForwards().stream().anyMatch(t -> t.getId().equals(tun.getId()))) {
            log.error("Tunnel with id {} already exists", tun.getId());
            return;
        }
        config.portForwards().add(tun);

        if (tun.isStartOnStartup() && tun.getMode() != Tunnel.Mode.SOCKS) {
            var runner = startTunnelRunner(tun);
            registerRunner(tun.getId(), runner);
        }

        if (persist) {
            persistConfig();
        }
    }

    static void removeTunnel(String id) {
        removeTunnel(id, true);
    }

    /** @param persist see {@link #addTunnel(Tunnel, boolean)}'s doc. */
    static void removeTunnel(String id, boolean persist) {
        if (config.portForwards().stream().noneMatch(t -> t.getId().equals(id))) {
            log.error("Tunnel with id {} does not exist", id);
            return;
        }
        stopTunnel(id);
        config.portForwards().removeIf(t -> t.getId().equals(id));

        if (persist) {
            persistConfig();
        }
    }

    /**
     * Public entry point for the webUI's "Save changes" button (edit mode): writes
     * whatever's currently in memory to disk, same as {@link #persistConfig()}
     * (which every non-staged {@code addTunnel}/{@code removeTunnel} call already
     * triggers automatically) — exposed separately so edit mode can defer the
     * actual write until the user explicitly confirms it.
     */
    static void saveChanges() {
        persistConfig();
    }

    /**
     * BIG-REFACTOR.md step 3.4 (+ later "edit tunnels" follow-up): write the
     * in-memory {@link #config} back to {@link #CONFIG_FILE} so additions/removals
     * survive a restart, via a write-new/rename-old/rename-new dance rather than
     * an in-place overwrite -- {@code <CONFIG_FILE>.new} is written first, the
     * existing file (if any) is renamed to {@code <CONFIG_FILE>.old}, then
     * {@code .new} is renamed onto the real path. This leaves the previous
     * config recoverable as {@code .old} and avoids ever leaving a half-written
     * config file at the real path if the write itself fails partway. What's
     * written is exactly what's already in memory, so the {@link
     * FileWatcher}-triggered {@link #reloadConfig()} that follows (the watcher
     * fires on this same file) diffs an equal set of tunnels back in and is a
     * no-op — see reloadConfig's id-based {@code Tunnel.equals} diffing, which
     * does not depend on write ordering or float/formatting round-trip issues
     * here since ports/durations round-trip losslessly (verified by
     * {@code ConfigurationRepositoryTest#saveConfigRoundTripsAllFieldsForAllModes}).
     */
    private static void persistConfig() {
        var newFile = new File(CONFIG_FILE.getPath() + ".new");
        var oldFile = new File(CONFIG_FILE.getPath() + ".old");
        try {
            ConfigurationRepository.saveConfig(newFile, config);
            if (CONFIG_FILE.exists()) {
                Files.move(CONFIG_FILE.toPath(), oldFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(newFile.toPath(), CONFIG_FILE.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("Failed to persist configuration to {}: {}", CONFIG_FILE, e.getMessage());
        }
    }

    static void reloadConfig() {
        try {
            log.info("Config changed. Reloading");
            var newConfig = ConfigurationRepository.loadConfig(CONFIG_FILE, DEFAULT_PORT);

            config.portForwards().stream().filter(tun -> !newConfig.portForwards().contains(tun)).filter(Objects::nonNull).toList().forEach(tun -> {
                if (!newConfig.portForwards().contains(tun)) {
                    removeTunnel(tun.getId(), false);
                    log.info("Removing tunnel {}", tun);
                }
            });

            newConfig.portForwards().stream().filter(tun -> !config.portForwards().contains(tun)).filter(Objects::nonNull).toList().forEach(tun -> {
                if (!config.portForwards().contains(tun)) {
                    addTunnel(tun, false);
                    log.info("Adding tunnel {}", tun);
                }
            });


            if (config.port() != newConfig.port()) {
                log.info("Port changed. Restarting server");
                config = config.withPort(newConfig.port());
                web.stop(0);
                web = new Web(config, Main::clusterTunnelStatus, Main::clusterConnectionCounts, Main::liveTunnelFor);
                web.start();
            }

            if (config.keepAliveInterval().compareTo(newConfig.keepAliveInterval()) != 0) {
                log.info("Keepalive interval changed. Restarting keepalive");
                config = config.withKeepAliveInterval(newConfig.keepAliveInterval());
                keepAlive.setKeepAliveInterval(newConfig.keepAliveInterval());
            }

            if (config.refreshInterval().compareTo(newConfig.refreshInterval()) != 0) {
                log.info("Refresh interval changed. Restarting server");
                config = config.withRefreshInterval(newConfig.refreshInterval());
                web.stop(0);
                web = new Web(config, Main::clusterTunnelStatus, Main::clusterConnectionCounts, Main::liveTunnelFor);
                web.start();
            }
        } catch (IOException | InterruptedException ex) {
            log.error(ex.getMessage());
        }
    }

}