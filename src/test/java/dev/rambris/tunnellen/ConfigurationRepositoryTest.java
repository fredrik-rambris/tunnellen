package dev.rambris.tunnellen;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ConfigurationRepositoryTest {

    private static final File FIXTURE = new File(
            ConfigurationRepositoryTest.class.getResource("/fixtures/sample-forwards.yaml").getFile());

    @Test
    void loadConfigParsesTopLevelFields() throws Exception {
        var config = ConfigurationRepository.loadConfig(FIXTURE, 3000);

        assertEquals(3000, config.port());
        assertFalse(config.killProc());
        assertEquals(Duration.ofSeconds(60), config.keepAliveInterval());
        assertEquals(Duration.ofSeconds(60), config.refreshInterval());
        assertEquals(2, config.groups().size());
        assertTrue(config.groups().contains("prod"));
        assertTrue(config.groups().contains("dev"));
    }

    @Test
    void loadConfigParsesPortForwardCount() throws Exception {
        var config = ConfigurationRepository.loadConfig(FIXTURE, 3000);

        assertEquals(3, config.portForwards().size());
    }

    @Test
    void loadConfigParsesHttpTunnelFields() throws Exception {
        var config = ConfigurationRepository.loadConfig(FIXTURE, 3000);

        var tunnel = config.portForwards().stream()
                .filter(t -> t.getLocalPort() == 9000)
                .findFirst()
                .orElseThrow();

        assertEquals("prod", tunnel.getGroup());
        assertEquals("my-prod-environment", tunnel.getContext());
        assertEquals("service/my-service", tunnel.getTarget());
        assertEquals("default", tunnel.getNamespace());
        assertEquals("8080", tunnel.getDestinationPort());
        assertTrue(tunnel.isStartOnStartup());
        assertEquals(Tunnel.Type.HTTP, tunnel.getType().orElseThrow());
        assertNull(tunnel.getDatabase());
    }

    @Test
    void loadConfigParsesDatabaseTunnelFields() throws Exception {
        var config = ConfigurationRepository.loadConfig(FIXTURE, 3000);

        var tunnel = config.portForwards().stream()
                .filter(t -> t.getLocalPort() == 9100)
                .findFirst()
                .orElseThrow();

        assertEquals(Tunnel.Type.DATABASE, tunnel.getType().orElseThrow());
        assertNotNull(tunnel.getDatabase());
        assertEquals(Database.Kind.POSTGRESQL, tunnel.getDatabase().kind());
        assertEquals("bigdatabase", tunnel.getDatabase().name());
        assertEquals("bigdbuser", tunnel.getDatabase().username());
    }

    @Test
    void loadConfigDefaultsModeWhenAbsentForBackwardCompatibility() throws Exception {
        var config = ConfigurationRepository.loadConfig(FIXTURE, 3000);

        for (var tunnel : config.portForwards()) {
            assertEquals(Tunnel.Mode.PORT_FORWARD, tunnel.getMode());
            assertTrue(tunnel.getDependsOn().isEmpty());
            assertTrue(tunnel.getSocksUsername().isEmpty());
            assertTrue(tunnel.getSocksPassword().isEmpty());
        }
    }

    @Test
    void loadConfigParsesAllThreeModes() throws Exception {
        var fixture = new File(
                ConfigurationRepositoryTest.class.getResource("/fixtures/socks-modes-forwards.yaml").getFile());
        var config = ConfigurationRepository.loadConfig(fixture, 3000);

        assertEquals(4, config.portForwards().size());

        var randomSocks = config.portForwards().stream()
                .filter(t -> t.getMode() == Tunnel.Mode.SOCKS)
                .filter(t -> t.getSocksUsername().isEmpty())
                .findFirst()
                .orElseThrow();
        assertEquals("bhg-dev", randomSocks.getContext());
        assertTrue(randomSocks.getDependsOn().isEmpty());
        assertTrue(randomSocks.getSocksUsername().isEmpty());
        assertTrue(randomSocks.getSocksPassword().isEmpty());

        var staticSocks = config.portForwards().stream()
                .filter(t -> t.getMode() == Tunnel.Mode.SOCKS)
                .filter(t -> t.getSocksUsername().isPresent())
                .findFirst()
                .orElseThrow();
        assertEquals("staticuser", staticSocks.getSocksUsername().orElseThrow());
        assertEquals("staticpass", staticSocks.getSocksPassword().orElseThrow());

        var service = config.portForwards().stream()
                .filter(t -> t.getMode() == Tunnel.Mode.SERVICE)
                .findFirst()
                .orElseThrow();
        assertEquals("bhg-dev", service.getDependsOn().orElseThrow());
        assertEquals("service/play-esales-search", service.getTarget());
        assertEquals(11402, service.getLocalPort());
        assertEquals(Tunnel.Type.HTTP, service.getType().orElseThrow());

        var legacyPortForward = config.portForwards().stream()
                .filter(t -> t.getLocalPort() == 11502)
                .findFirst()
                .orElseThrow();
        assertEquals(Tunnel.Mode.PORT_FORWARD, legacyPortForward.getMode());
        assertTrue(legacyPortForward.getDependsOn().isEmpty());
    }

    /**
     * BIG-REFACTOR.md step 3.4: build a {@link Configuration} covering every field
     * variation (plain HTTP, DATABASE, SOCKS cluster tunnel with/without static
     * credentials, and a SERVICE tunnel with dependsOn), save it, reload it, and
     * assert every field on every tunnel survived the round trip.
     */
    @Test
    void saveConfigRoundTripsAllFieldsForAllModes() throws Exception {
        var httpTunnel = new Tunnel(
                "prod", "my-prod-environment", "service/my-service", "default",
                9000, "8080", true, Tunnel.Type.HTTP, null,
                Tunnel.Mode.PORT_FORWARD, null, null, null);

        var databaseTunnel = new Tunnel(
                "prod", "my-prod-environment", "service/my-db", "default",
                9100, "5432", true, Tunnel.Type.DATABASE,
                new Database(Database.Kind.POSTGRESQL, "bigdatabase", "bigdbuser"),
                Tunnel.Mode.PORT_FORWARD, null, null, null);

        var randomSocksTunnel = new Tunnel(
                "dev", "bhg-dev", null, "default",
                19000, null, false, null, null,
                Tunnel.Mode.SOCKS, null, null, null);

        var staticSocksTunnel = new Tunnel(
                "dev", "bhg-dev", null, "default",
                19001, null, false, null, null,
                Tunnel.Mode.SOCKS, null, "staticuser", "staticpass");

        var serviceTunnel = new Tunnel(
                "dev", "bhg-dev", "service/play-esales-search", "default",
                11402, "8080", true, Tunnel.Type.HTTP, null,
                Tunnel.Mode.SERVICE, "bhg-dev", null, null);

        var original = new Configuration(
                List.of("prod", "dev"),
                new java.util.ArrayList<>(List.of(httpTunnel, databaseTunnel, randomSocksTunnel, staticSocksTunnel, serviceTunnel)),
                Duration.ofMinutes(2),
                Duration.ofSeconds(45),
                3000,
                true,
                Optional.of("fredrik"));

        var tempFile = Files.createTempFile("tunnellen-roundtrip", ".yaml").toFile();
        tempFile.deleteOnExit();
        try {
            ConfigurationRepository.saveConfig(tempFile, original);
            var reloaded = ConfigurationRepository.loadConfig(tempFile, 3000);

            assertEquals(original.port(), reloaded.port());
            assertEquals(original.killProc(), reloaded.killProc());
            assertEquals(original.keepAliveInterval(), reloaded.keepAliveInterval());
            assertEquals(original.refreshInterval(), reloaded.refreshInterval());
            assertEquals(new java.util.HashSet<>(original.groups()), new java.util.HashSet<>(reloaded.groups()));
            assertEquals(original.socksPodSuffix(), reloaded.socksPodSuffix());
            assertEquals(original.portForwards().size(), reloaded.portForwards().size());

            assertTunnelMatches(httpTunnel, findByLocalPort(reloaded, 9000));
            assertTunnelMatches(databaseTunnel, findByLocalPort(reloaded, 9100));
            assertTunnelMatches(randomSocksTunnel, findByLocalPort(reloaded, 19000));
            assertTunnelMatches(staticSocksTunnel, findByLocalPort(reloaded, 19001));
            assertTunnelMatches(serviceTunnel, findByLocalPort(reloaded, 11402));
        } finally {
            Files.deleteIfExists(tempFile.toPath());
        }
    }

    @Test
    void sortedForSavePutsSocksTunnelsFirstThenRestByLocalPort() {
        var socksB = new Tunnel("test", "bhg-test", null, null, 0, null, true, null, null,
                Tunnel.Mode.SOCKS, null, null, null);
        var socksA = new Tunnel("prod", "bhg-prod", null, null, 0, null, true, null, null,
                Tunnel.Mode.SOCKS, null, null, null);
        var highPort = new Tunnel("dev", "bhg-dev", "service/z", "default", 30000, "8080", true, Tunnel.Type.HTTP, null);
        var lowPort = new Tunnel("prod", "bhg-prod", "service/a", "default", 10000, "8080", true, Tunnel.Type.HTTP, null);

        var sorted = ConfigurationRepository.sortedForSave(List.of(highPort, socksB, lowPort, socksA));

        assertEquals(List.of(socksB, socksA, lowPort, highPort), sorted);
    }

    @Test
    void saveConfigSeparatesEachTunnelWithABlankLine() throws IOException {
        var tunnels = new ArrayList<Tunnel>(List.of(
                new Tunnel("prod", "bhg-prod", "service/a", "default", 10000, "8080", true, Tunnel.Type.HTTP, null),
                new Tunnel("test", "bhg-test", "service/a", "default", 10001, "8080", true, Tunnel.Type.HTTP, null)));
        var config = new Configuration(List.of("prod", "test"), tunnels, Duration.ofMinutes(1), Duration.ofMinutes(1),
                3000, false, Optional.empty());

        var tempFile = Files.createTempFile("tunnellen-spacing", ".yaml").toFile();
        tempFile.deleteOnExit();
        try {
            ConfigurationRepository.saveConfig(tempFile, config);
            var lines = Files.readAllLines(tempFile.toPath());
            var blankLineCount = lines.stream().filter(String::isBlank).count();
            // One blank line between the two tunnels; not after the last one.
            assertEquals(1, blankLineCount);
            assertTrue(lines.get(lines.size() - 1).isBlank() == false, "should not end with a trailing blank line");
        } finally {
            Files.deleteIfExists(tempFile.toPath());
        }
    }

    private static Tunnel findByLocalPort(Configuration config, int localPort) {
        return config.portForwards().stream()
                .filter(t -> t.getLocalPort() == localPort)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No tunnel with localPort " + localPort));
    }

    private static void assertTunnelMatches(Tunnel expected, Tunnel actual) {
        assertEquals(expected.getGroup(), actual.getGroup());
        assertEquals(expected.getContext(), actual.getContext());
        assertEquals(expected.getTarget(), actual.getTarget());
        assertEquals(expected.getNamespace(), actual.getNamespace());
        assertEquals(expected.getLocalPort(), actual.getLocalPort());
        assertEquals(expected.getDestinationPort(), actual.getDestinationPort());
        assertEquals(expected.isStartOnStartup(), actual.isStartOnStartup());
        assertEquals(expected.getType(), actual.getType());
        assertEquals(expected.getDatabase(), actual.getDatabase());
        assertEquals(expected.getMode(), actual.getMode());
        assertEquals(expected.getDependsOn(), actual.getDependsOn());
        assertEquals(expected.getSocksUsername(), actual.getSocksUsername());
        assertEquals(expected.getSocksPassword(), actual.getSocksPassword());
    }
}
