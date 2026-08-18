package dev.rambris.tunnellen;

import ch.qos.logback.classic.Logger;
import org.slf4j.LoggerFactory;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.common.FlowStyle;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

public class ConfigurationRepository {
    private static final Logger log = (Logger) LoggerFactory.getLogger(ConfigurationRepository.class);


    static Configuration loadConfig(File file, int defaultPort) throws IOException {
        var config = new Configuration(List.of(), List.of(), Duration.ofMinutes(1), Duration.ofMinutes(1), defaultPort, false, Optional.empty());


        try (var in = new FileReader(file)) {
            log.atInfo().log("Loading configuration");
            var settings = LoadSettings.builder().build();
            var load = new Load(settings);

            var yaml = (Map<String, Object>) load.loadFromReader(in);
            config = Optional.ofNullable(yaml)
                    .map(m -> new Configuration(
                            parseGroups(m.get("groups")),
                            parsePortForwards(m.get("portForwards")),
                            parseDuration(m.get("keepAliveInterval"), Duration.ofMinutes(1)),
                            parseDuration(m.get("refreshInterval"), Duration.ofMinutes(1)),
                            getAsInt(m.getOrDefault("port", 3000)),
                            getAsBoolean(m.get("killProc"), false),
                            Optional.ofNullable((String) m.get("socksPodSuffix")).filter(s -> !s.isBlank())
                            ))
                    .orElse(config);
        } catch(IOException e) {
            System.err.println("Could not load config file: " + e.getMessage());
            System.exit(1);
        }

        return config;
    }

    /**
     * BIG-REFACTOR.md step 3.4: serialize a {@link Configuration} back to YAML in
     * exactly the shape {@link #loadConfig} expects to read, so additions/removals
     * made at runtime (e.g. via the webUI) survive a restart. Comment preservation
     * is not attempted (SnakeYAML-Engine doesn't support it) — this always produces
     * a clean, regenerated file.
     */
    /**
     * Writes {@code config} to {@code file} as YAML, laid out for readability
     * rather than as one compact block: cluster {@code mode: socks} tunnels are
     * listed first (they're infrastructure, and there are only ever a handful),
     * the rest follow sorted by {@code localPort} (so the file reads top-to-bottom
     * the same way the {@code SSSEE} port-numbering scheme already groups
     * services), and a blank line separates each {@code portForwards} entry so a
     * human editing the file by hand can actually see where one tunnel ends and
     * the next begins. {@link ConfigurationRepositoryTest#saveConfigRoundTripsAllFieldsForAllModes}
     * confirms this reordering/spacing doesn't affect what {@link #loadConfig}
     * reads back.
     */
    static void saveConfig(File file, Configuration config) throws IOException {
        var root = new LinkedHashMap<String, Object>();
        root.put("port", config.port());
        root.put("killProc", config.killProc());
        root.put("keepAliveInterval", config.keepAliveInterval().toString());
        root.put("refreshInterval", config.refreshInterval().toString());
        root.put("groups", new ArrayList<>(config.groups()));
        config.socksPodSuffix().ifPresent(s -> root.put("socksPodSuffix", s));

        var settings = DumpSettings.builder()
                .setDefaultFlowStyle(FlowStyle.BLOCK)
                .build();
        var dump = new Dump(settings);

        try (var out = new FileWriter(file)) {
            out.write(dump.dumpToString(root));
            out.write("portForwards:\n");
            var sorted = sortedForSave(config.portForwards());
            for (int i = 0; i < sorted.size(); i++) {
                // Dumping a one-element list re-uses SnakeYAML's own block-sequence
                // rendering for a single "- key: value" entry (indentation etc. stays
                // exactly as loadConfig expects), rather than hand-formatting YAML.
                out.write(dump.dumpToString(List.of(dumpTunnel(sorted.get(i)))));
                if (i < sorted.size() - 1) {
                    out.write("\n");
                }
            }
        }
    }

    /**
     * Cluster {@code mode: socks} tunnels first (stable order: as they appear in
     * {@code portForwards}), then everything else sorted by {@code localPort}
     * ascending. Pure, package-visible for unit tests.
     */
    static List<Tunnel> sortedForSave(List<Tunnel> tunnels) {
        var socksTunnels = tunnels.stream().filter(t -> t.getMode() == Tunnel.Mode.SOCKS).toList();
        var rest = tunnels.stream()
                .filter(t -> t.getMode() != Tunnel.Mode.SOCKS)
                .sorted(Comparator.comparingInt(Tunnel::getLocalPort))
                .toList();
        var result = new ArrayList<Tunnel>(tunnels.size());
        result.addAll(socksTunnels);
        result.addAll(rest);
        return result;
    }

    private static Map<String, Object> dumpTunnel(Tunnel tunnel) {
        var m = new LinkedHashMap<String, Object>();
        m.put("context", tunnel.getContext());
        if (tunnel.getTarget() != null) {
            m.put("target", tunnel.getTarget());
        }
        if (tunnel.getNamespace() != null) {
            m.put("namespace", tunnel.getNamespace());
        }
        m.put("localPort", tunnel.getLocalPort());
        if (tunnel.getDestinationPort() != null) {
            m.put("remotePort", tunnel.getDestinationPort());
        }
        m.put("startOnStartup", tunnel.isStartOnStartup());
        tunnel.getType().ifPresent(t -> m.put("type", t.name().toLowerCase(Locale.ROOT)));
        m.put("group", tunnel.getGroup());

        if (tunnel.getMode() != Tunnel.Mode.PORT_FORWARD) {
            m.put("mode", switch (tunnel.getMode()) {
                case SOCKS -> "socks";
                case SERVICE -> "service";
                case PORT_FORWARD -> "port-forward";
            });
        }
        tunnel.getDependsOn().ifPresent(d -> m.put("dependsOn", d));
        tunnel.getSocksUsername().ifPresent(u -> m.put("socksUsername", u));
        tunnel.getSocksPassword().ifPresent(p -> m.put("socksPassword", p));

        if (tunnel.getDatabase() != null) {
            var db = tunnel.getDatabase();
            var dbMap = new LinkedHashMap<String, Object>();
            dbMap.put("kind", db.kind().name().toLowerCase(Locale.ROOT));
            dbMap.put("name", db.name());
            dbMap.put("username", db.username());
            m.put("database", dbMap);
        }

        return m;
    }

    private static List<String> parseGroups(Object o) {
        return Optional.ofNullable(o)
                .filter(List.class::isInstance)
                .map(l -> (List<String>) l)
                .orElseGet(ArrayList::new);
    }

    private static List<Tunnel> parsePortForwards(Object o) {
        return Optional.ofNullable(o)
                .filter(List.class::isInstance)
                .map(l -> (List<Map<String, Object>>) l)
                .stream().flatMap(Collection::stream)
                .map(Map.class::cast)
                .map(m -> new Tunnel(
                        (String) Optional.ofNullable(m.get("group")).orElse("default"),
                        (String) m.get("context"),
                        (String) m.get("target"),
                        Optional.ofNullable((String) m.get("namespace")).orElse("default"),
                        Optional.ofNullable(getAsInt(m.get("localPort"))).orElse(0),
                        getIntAsString(m.get("remotePort")),
                        getAsBoolean(m.get("startOnStartup"), false),
                        Optional.ofNullable((String) m.get("type")).map(String::toUpperCase).map(Tunnel.Type::valueOf).orElse(null),
                        parseDatabase(m.get("database")),
                        parseMode(m.get("mode")),
                        (String) m.get("dependsOn"),
                        (String) m.get("socksUsername"),
                        (String) m.get("socksPassword")
                ))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static Tunnel.Mode parseMode(Object o) {
        if (o instanceof String s) {
            switch (s.toLowerCase(Locale.ROOT)) {
                case "port-forward", "port_forward":
                    return Tunnel.Mode.PORT_FORWARD;
                case "socks":
                    return Tunnel.Mode.SOCKS;
                case "service":
                    return Tunnel.Mode.SERVICE;
                default:
                    log.warn("Unknown tunnel mode '{}', defaulting to PORT_FORWARD", s);
            }
        }
        return Tunnel.Mode.PORT_FORWARD;
    }

    private static Database parseDatabase(Object o) {
        return Optional.ofNullable(o)
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(m -> new Database(
                        Database.Kind.valueOf(((String) m.get("kind")).toUpperCase()),
                        (String) m.get("name"),
                        (String) m.get("username")
                ))
                .orElse(null);
    }

    private static Integer getAsInt(Object o) {
        if (o instanceof Integer) {
            return (Integer) o;
        }
        if (o instanceof String) {
            return Integer.parseInt((String) o);
        }
        return null;
    }

    private static String getIntAsString(Object o) {
        if (o instanceof Integer) {
            return String.valueOf(o);
        }
        if (o instanceof String) {
            return (String) o;
        }
        return null;
    }

    private static boolean getAsBoolean(Object o, boolean defaultValue) {
        if (o instanceof Boolean) {
            return (Boolean) o;
        }
        if (o instanceof String) {
            return Boolean.parseBoolean((String) o);
        }
        return defaultValue;
    }

    private static Duration parseDuration(Object o, Duration defaultValue) {
        if (o instanceof Integer) {
            return Duration.ofSeconds((Integer) o);
        }
        if (o instanceof String) {
            return Duration.parse((String) o);
        }
        return defaultValue;
    }
}
