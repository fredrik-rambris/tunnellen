package dev.rambris.tunnellen;

import ch.qos.logback.classic.Logger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
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

        var response = switch (httpExchange.getRequestURI().getPath()) {
            case "/list" -> listTunnels();
            case "/" -> index();
            case "/startTunnel" -> {
                httpExchange.getResponseHeaders().set("Refresh", "5; url=/list");
                Main.startTunnel(query.get("id"));
                yield header("Starting tunnel") + "OK" + footer();
            }
            case "/stopTunnel" -> {
                httpExchange.getResponseHeaders().set("Refresh", "5; url=/list");
                Main.stopTunnel(query.get("id"));
                yield header("Stopping tunnel") + "OK" + footer();
            }
            case "/style.css" -> styles(httpExchange);
            default -> null;
        };
        if (response != null) {
            httpExchange.sendResponseHeaders(200, response.getBytes().length);
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

    private String listTunnels() {
        return header("Tunnels - Tunnellen") + "<table>\n<thead>\n<tr><th>Context</th><th>Target</th><th>Local port</th><th>Destination port</th><th>Last checked</th><th>State</th></tr>\n</thead>\n<tbody>\n" +
               config.portForwards().stream().sorted(Comparator.comparing(Tunnel::isStarted).reversed().thenComparing(Tunnel::getTarget).thenComparing(Tunnel::getContext)).map(this::tunnel).collect(Collectors.joining("\n")) +
               "\n</tbody>\n</table>\n" + footer();
    }

    private String tunnel(Tunnel tun) {
        return """
                <tr>
                <td>%s</td>
                <td>%s</td>
                <td>%d</td>
                <td>%s</td>
                <td>%s</td>
                <td>%s</td>
                </tr>
                """.formatted(
                tun.getContext(),
                tun.getTarget() + Optional.ofNullable(tun.getNamespace()).filter(f -> !f.equalsIgnoreCase("default")).map(ns -> " (" + ns + ")").orElse(""),
                tun.getLocalPort(),
                tun.getDestinationPort(),
                Optional.ofNullable(tun.getLastCheck()).filter(x -> !x.isEqual(LocalDateTime.MIN)).map(lc -> lc.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).orElse(""),
                (tun.isRunning() ? "<a href=\"/stopTunnel?id=" + tun.getId() + "\">Running</a>" : "<a href=\"/startTunnel?id=" + tun.getId() + "\">Stopped</a>")

        );
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
                </body>
                </html>
                """;
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
                }
                main {
                    margin: 1rem;
                }
                table {
                    border-collapse: collapse;
                    width: 100%;
                }
                th, td {
                    border: 1px solid #ddd;
                    padding: 8px;
                    text-align: left;
                }
                th {
                    background-color: #f2f2f2;
                }
                tr:nth-child(even) {
                    background-color: #f2f2f2;
                }
                tr:hover {
                    background-color: #f0f0ff;
                }
                a {
                    text-decoration: none;
                    color: blue;
                }
                a:hover {
                    text-decoration: underline;
                }
                """.strip();
    }
}
