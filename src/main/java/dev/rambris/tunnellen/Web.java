package dev.rambris.tunnellen;

import ch.qos.logback.classic.Logger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


public class Web {

    private static final Logger log = (Logger) LoggerFactory.getLogger(Web.class);

    private Configuration config;
    private HttpServer server;

    public Web(Configuration config) throws IOException {
        this.config = config;
        this.server = HttpServer.create(new InetSocketAddress(config.port()), 0);
        this.server.createContext("/", this::handleHttp);
    }

    public void start() {
        server.start();
    }

    public void stop(int delay) {
        server.stop(delay);
    }

    private void handleHttp(HttpExchange httpExchange) throws IOException {
        if (httpExchange.getRequestURI().getPath().startsWith("/favicon.ico")) {
            httpExchange.sendResponseHeaders(404, 0);
            return;
        }
        log.info("Handling request. {}", httpExchange.getRequestURI());
        if (!httpExchange.getRequestMethod().equalsIgnoreCase("GET")) {
            httpExchange.sendResponseHeaders(405, 0);
            return;
        }
        httpExchange.getResponseHeaders().set("Content-Type", "text/html");
        var query = decodeQuery(httpExchange.getRequestURI().getQuery());

        int responseCode = 200;

        var response = switch (httpExchange.getRequestURI().getPath()) {
            case "/list" -> listTunnels(httpExchange);
            case "/" -> index();
            case "/startTunnel" -> {
                httpExchange.getResponseHeaders().set("Refresh", "0 url=/list");
                httpExchange.getResponseHeaders().set("Location", "/list");
                responseCode = 302;
                Main.startTunnel(query.get("id"));
                yield header("Starting tunnel") + "OK" + footer();
            }
            case "/stopTunnel" -> {
                httpExchange.getResponseHeaders().set("Refresh", "0 url=/list");
                httpExchange.getResponseHeaders().set("Location", "/list");
                responseCode = 302;
                Main.stopTunnel(query.get("id"));
                yield header("Stopping tunnel") + "OK" + footer();
            }
            case "/restartTunnel" -> {
                httpExchange.getResponseHeaders().set("Refresh", "0 url=/list");
                httpExchange.getResponseHeaders().set("Location", "/list");
                responseCode = 302;
                Main.stopTunnel(query.get("id"));
                Main.startTunnel(query.get("id"));
                yield header("Restarting tunnel") + "OK" + footer();
            }
            case "/style.css" -> styles(httpExchange);
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
            log.info("Not fonud");
        }
    }

    private Map<String, String> decodeQuery(String query) {
        return query != null ? Pattern.compile("&")
                .splitAsStream(query)
                .map(s -> Arrays.copyOf(s.split("=", 2), 2))
                .map(o -> Map.entry(decode(o[0]), decode(o[1])))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)) : Map.of();
    }

    private String decode(final String encoded) {
        return Optional.ofNullable(encoded)
                .map(e -> URLDecoder.decode(e, StandardCharsets.UTF_8))
                .orElse(null);
    }

    private String listTunnels(HttpExchange httpExchange) {
        if (config.refreshInterval().toSeconds() > 0) {
            httpExchange.getResponseHeaders().set("Refresh", "%d url=/list".formatted(config.refreshInterval().toSeconds()));
        }

        var host = getHost(httpExchange);

        return header("Tunnels - Tunnellen") + "<table>\n<thead>\n<tr><th>Context</th><th>Target</th><th>Local port</th><th>State</th></tr>\n</thead>\n<tbody>\n" +
               config.portForwards().stream().sorted(Comparator.comparing(Tunnel::isStartOnStartup).reversed().thenComparing(Tunnel::getTarget).thenComparing(Tunnel::getContext)).map(t -> tunnel(t, host)).collect(Collectors.joining("\n")) +
               "\n</tbody>\n</table>\n" + footer();
    }

    private static String getHost(HttpExchange httpExchange) {
        return Optional.ofNullable(httpExchange.getRequestHeaders().getFirst("Host")).map(h -> h.split(":")[0]).orElse("127.0.0.1");
    }

    private String tunnel(Tunnel tun, String host) {
        return """
                <tr>
                <td>%s</td>
                <td>%s</td>
                <td>%d</td>
                <td>%s</td>
                </tr>
                """.formatted(
                tun.getContext(),
                target(tun),
                tun.getLocalPort(),
                actionIcons(tun, host)


        );
    }

    private String target(Tunnel tun) {
        return tun.getTarget() + "<span class=\"notimportant\">:" + tun.getDestinationPort() + Optional.ofNullable(tun.getNamespace()).filter(f -> !f.equalsIgnoreCase("default")).map(ns -> " (" + ns + ")").orElse("") + "</span>";
    }

    private String actionIcons(Tunnel tun, String host) {
        var startStop = tun.isRunning() ? """
                <a href="/stopTunnel?id=%s" class="running" title="Stop tunnel">&#x23F9;</a>
                <a href="/restartTunnel?id=%s" class="running" title="Restart tunnel">&#x27F3;</a>
                <span class="notimportant">%s</span>
                """.formatted(tun.getId(), tun.getId(), Optional.ofNullable(tun.getLastCheck()).filter(lc -> lc.isAfter(LocalDateTime.MIN)).map(lc -> Duration.between(lc, LocalDateTime.now()).toSeconds()).map(lc -> lc + " s").orElse("")) : """
                <a href="/startTunnel?id=%s" class="stopped" title="Start tunnel">&#x23F5;</a>
                """.formatted(tun.getId());

        var db = tun.getType().filter(Tunnel.Type.DATABASE::equals).map(t -> """
                <a href="/intellij?id=%s" target="_blank" class="iconlink" title="Generate IntelliJ Datasource">&#x1F5C2;</a>
                """.formatted(tun.getId())).orElse("");

        var http = tun.getType().filter(Tunnel.Type.HTTP::equals).map(t -> "<a href=\"http://%s:%d\" class=\"iconlink\" target=\"_blank\">&#x1F517;</a>".formatted(host, tun.getLocalPort())).orElse("");


        return startStop + db + http;
    }

    private String header(String title) {
        return """
                <!doctype html>
                <html>
                <head>
                <title>%s</title>
                <link rel="stylesheet" href="/style.css">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                </head>
                <body>
                <main>
                """.formatted(title);
    }

    private String footer() {
        return """
                </main>
                <footer>
                <p>Version: %s. Copyright &copy; 2023 Fredrik Rambris</p>
                </footer>
                </body>
                </html>
                """.formatted(Main.VERSION.getVersion());
    }

    private String index() {
        return header("Tunnellen") +
               "<h1>Tunnellen</h1>\n" +
               "<a href=\"/list\">List tunnels</a>\n" +
               footer();
    }

    private String styles(HttpExchange httpExchange) {
        httpExchange.getResponseHeaders().set("Content-Type", "text/css");
        httpExchange.getResponseHeaders().set("Cache-Control", "max-age=300");
        return """
                html { margin: 0; padding: 0; }
                body {
                    font-family: Arial, sans-serif;
                    margin: 0; padding: 0;
                    background-color: #151515;
                    color: #b0b0b0;
                }
                main {
                    margin: 1rem;
                    width: fit-content;
                    block-size: fit-content;
                
                }
                footer {
                    margin: 1rem;
                    color: #888;
                    font-size: 75%;
                    border-top: 1px solid #282828;
                }
                table {
                    border-collapse: collapse;
                }
                th, td {
                    border: 1px solid #444;
                    padding: 8px;
                    text-align: left;
                }
                th {
                    background-color: #282828;
                }
                tr:nth-child(even) {
                    background-color: #1c1c1c;
                }
                tr:hover {
                    background-color: #222;
                }
                a {
                    text-decoration: none;
                    color: #ddd;
                    font-weight: bold;
                }
                a:hover {
                    text-decoration: underline;
                }
                .notimportant {
                    color: #888;
                    font-size: 75%;
                }
                
                .running {
                    color: green;
                }
                .stopped {
                    color: red;
                }
                
                .running:hover, .stopped:hover, .iconlink:hover {
                    text-decoration: none;
                }
                """.strip();
    }


    private String intellij(String id, String host) {
        return config.portForwards().stream().filter(t -> t.getId().equals(id) && t.getType().isPresent() && t.getType().get() == Tunnel.Type.DATABASE && t.getDatabase() != null).findFirst().map(t -> {
            var db = t.getDatabase();
            return generateDatasource(t.getContext(), db, host, t.getLocalPort());
        }).orElse("Not found");
    }

    static String generateDatasource(String env, Database db, String proxyHost, int localPort) {
        var name = db.name() + "-" + env;
        var uuid = UUID.randomUUID().toString();
        var jdbcUrl = "jdbc:%s://%s:%d/%s".formatted(db.kind().jdbcPrefix, proxyHost, localPort, db.name());

        var group = env.substring(0, 1).toUpperCase() + env.substring(1);

        return """
                #DataSourceSettings#
                #LocalDataSource: %s
                #BEGIN#
                <?xml version="1.0"?>
                <data-source source="LOCAL" name="%s" group="%s" uuid="%s">
                  <database-info product="%s" version="" jdbc-version="%s" driver-name="%s" driver-version="%s" dbms="%s" exact-version="" exact-driver-version="%s">
                  <identifier-quote-string >%s</identifier-quote-string>
                </database-info>
                  <case-sensitivity plain-identifiers="lower" quoted-identifiers="exact"/>
                  <driver-ref>%s</driver-ref>
                  <synchronize>true</synchronize>
                  <jdbc-driver>%s</jdbc-driver>
                  <jdbc-url>%s</jdbc-url>
                  <secret-storage>master_key</secret-storage>
                  <user-name>%s</user-name>
                  <schema-mapping>
                    <introspection-scope>
                      <node kind="database" qname="@">
                        <node kind="schema" qname="@"/>
                      </node>
                    </introspection-scope>
                  </schema-mapping>
                  <working-dir>$ProjectFileDir$</working-dir>
                </data-source>
                #END#
                """.formatted(
                name,
                name,
                group,
                uuid,
                db.kind().product,
                db.kind().jdbcVersion,
                db.kind().driverName,
                db.kind().driverVersion,
                db.kind().dbms,
                db.kind().exactDriverVersion,
                db.kind().identifierQuoteString,
                db.kind().jdbcPrefix,
                db.kind().driverClass,
                jdbcUrl,
                db.username()
        );
    }
}
