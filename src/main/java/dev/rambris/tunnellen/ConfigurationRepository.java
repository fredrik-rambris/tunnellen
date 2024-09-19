package dev.rambris.tunnellen;

import ch.qos.logback.classic.Logger;
import org.slf4j.LoggerFactory;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

public class ConfigurationRepository {
    private static final Logger log = (Logger) LoggerFactory.getLogger(ConfigurationRepository.class);


    static Configuration loadConfig(File file, int defaultPort) throws IOException {
        var config = new Configuration(List.of(), List.of(), Duration.ofMinutes(1), Duration.ofMinutes(1), defaultPort);


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
                            getAsInt(m.getOrDefault("port", 3000))))
                    .orElse(config);
        } catch(IOException e) {
            System.err.println("Could not load config file: " + e.getMessage());
            System.exit(1);
        }

        return config;
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
                        getAsInt(m.get("localPort")),
                        getIntAsString(m.get("remotePort")),
                        getAsBoolean(m.get("startOnStartup"), false),
                        Optional.ofNullable((String) m.get("type")).map(String::toUpperCase).map(Tunnel.Type::valueOf).orElse(null),
                        parseDatabase(m.get("database"))
                ))
                .collect(Collectors.toCollection(ArrayList::new));
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
