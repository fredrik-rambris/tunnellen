package dev.rambris.tunnellen;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WebTest {

    private static Tunnel tunnel(String group, String context, String target, int localPort) {
        return new Tunnel(group, context, target, "default", localPort, "8080", true, Tunnel.Type.HTTP, null);
    }

    private static Configuration configWithPlayApiFixture() {
        // Mirrors the shape of BIG-REFACTOR.md's "querying play-api" example:
        // service/play-api-public across bhg-prod/bhg-test/bhg-dev, plus a couple
        // of unrelated services to prove the substring match doesn't over-match.
        var tunnels = List.of(
                tunnel("prod", "bhg-prod", "service/play-api-public", 10000),
                tunnel("test", "bhg-test", "service/play-api-public", 10001),
                tunnel("dev", "bhg-dev", "service/play-api-public", 10002),
                tunnel("prod", "bhg-prod", "service/play-esales-search", 11400),
                tunnel("prod", "bhg-prod", "deployment/other-service", 12000)
        );
        return new Configuration(List.of("prod", "test", "dev"), tunnels, Duration.ofSeconds(60), Duration.ofSeconds(60), 3000, false, Optional.empty());
    }

    @Test
    void bareTargetNameStripsKindPrefix() {
        assertEquals("play-api-public", Web.bareTargetName("service/play-api-public"));
        assertEquals("other-service", Web.bareTargetName("deployment/other-service"));
        assertEquals("no-prefix", Web.bareTargetName("no-prefix"));
        assertEquals("", Web.bareTargetName(null));
    }

    @Test
    void lookupServiceMatchesCaseInsensitiveSubstringAcrossGroups() {
        var config = configWithPlayApiFixture();

        var matches = Web.lookupService(config, "play-api");

        assertEquals(3, matches.size());
        assertEquals(List.of("bhg-prod", "bhg-test", "bhg-dev"), matches.stream().map(Tunnel::getContext).toList());
        assertEquals(List.of(10000, 10001, 10002), matches.stream().map(Tunnel::getLocalPort).toList());
    }

    @Test
    void lookupServiceIsCaseInsensitive() {
        var config = configWithPlayApiFixture();

        var matches = Web.lookupService(config, "PLAY-API");

        assertEquals(3, matches.size());
    }

    @Test
    void lookupServiceOrdersByConfiguredGroupOrderThenContext() {
        var tunnels = List.of(
                tunnel("dev", "z-dev", "service/foo", 1),
                tunnel("prod", "a-prod", "service/foo", 2),
                tunnel("test", "m-test", "service/foo", 3),
                tunnel("unknown-group", "b-unknown", "service/foo", 4)
        );
        var config = new Configuration(List.of("prod", "test", "dev"), tunnels, Duration.ofSeconds(60), Duration.ofSeconds(60), 3000, false, Optional.empty());

        var matches = Web.lookupService(config, "foo");

        assertEquals(List.of("a-prod", "m-test", "z-dev", "b-unknown"), matches.stream().map(Tunnel::getContext).toList());
    }

    @Test
    void lookupServiceReturnsEmptyForNoMatch() {
        var config = configWithPlayApiFixture();

        assertTrue(Web.lookupService(config, "totally-unknown-service").isEmpty());
    }

    @Test
    void lookupServiceInContextFiltersToExactContext() {
        var config = configWithPlayApiFixture();

        var matches = Web.lookupServiceInContext(config, "play-api", "bhg-dev");

        assertEquals(1, matches.size());
        assertEquals(10002, matches.get(0).getLocalPort());
    }

    @Test
    void lookupServiceInContextReturnsEmptyWhenContextDoesNotMatch() {
        var config = configWithPlayApiFixture();

        assertTrue(Web.lookupServiceInContext(config, "play-api", "no-such-context").isEmpty());
    }

    @Test
    void lookupServiceInContextCanBeAmbiguousWithinSameContext() {
        var tunnels = List.of(
                tunnel("prod", "bhg-prod", "service/play-api-public", 10000),
                tunnel("prod", "bhg-prod", "service/play-api-internal", 10001)
        );
        var config = new Configuration(List.of("prod"), tunnels, Duration.ofSeconds(60), Duration.ofSeconds(60), 3000, false, Optional.empty());

        var matches = Web.lookupServiceInContext(config, "play-api", "bhg-prod");

        assertEquals(2, matches.size(), "both should match the substring query within the same context - ambiguous, caller 404s");
    }

    // --- 3.1: candidateServices dedup logic ---

    @Test
    void candidateServicesFiltersOutAlreadyConfiguredServicesInSameContext() {
        var config = configWithPlayApiFixture();
        var discovered = List.of(
                new K8sService("play-api-public", "default", List.of()),
                new K8sService("brand-new-service", "default", List.of())
        );

        var candidates = Web.candidateServices(config, "bhg-prod", discovered);

        assertEquals(1, candidates.size());
        assertEquals("brand-new-service", candidates.get(0).name());
    }

    @Test
    void candidateServicesDoesNotFilterServiceConfiguredOnlyInDifferentContext() {
        var config = configWithPlayApiFixture();
        // play-api-public IS configured, but not for "some-other-context"
        var discovered = List.of(new K8sService("play-api-public", "default", List.of()));

        var candidates = Web.candidateServices(config, "some-other-context", discovered);

        assertEquals(1, candidates.size());
    }

    @Test
    void candidateServicesMatchIsCaseInsensitive() {
        var config = configWithPlayApiFixture();
        var discovered = List.of(new K8sService("PLAY-API-PUBLIC", "default", List.of()));

        var candidates = Web.candidateServices(config, "bhg-prod", discovered);

        assertTrue(candidates.isEmpty());
    }

    // --- 3.2 (inline duplicate): port allocation ---

    @Test
    void nextServiceIndexIsOneHundredForEmptyConfig() {
        var config = new Configuration(List.of("prod", "test", "dev"), List.of(), Duration.ofSeconds(60), Duration.ofSeconds(60), 3000, false, Optional.empty());

        assertEquals(100, Web.nextServiceIndex(config));
    }

    @Test
    void nextServiceIndexIsMaxPlusOneIgnoringEnvSuffix() {
        var config = configWithPlayApiFixture(); // highest localPort is 12000 -> index 120

        assertEquals(121, Web.nextServiceIndex(config));
    }

    @Test
    void nextServiceIndexMatchesBigRefactorFixtureExample() {
        // Per BIG-REFACTOR.md 3.2: highest index currently in use is 125
        // (bokus-reader-service-public at 12500/01/02) -> next allocation is 126.
        var tunnels = List.of(
                tunnel("prod", "bhg-prod", "service/bokus-reader-service-public", 12500),
                tunnel("test", "bhg-test", "service/bokus-reader-service-public", 12501),
                tunnel("dev", "bhg-dev", "service/bokus-reader-service-public", 12502)
        );
        var config = new Configuration(List.of("prod", "test", "dev"), tunnels, Duration.ofSeconds(60), Duration.ofSeconds(60), 3000, false, Optional.empty());

        assertEquals(126, Web.nextServiceIndex(config));
    }

    @Test
    void portsForServiceIndexComputesProdTestDevTriad() {
        assertArrayEquals(new int[]{12600, 12601, 12602}, Web.portsForServiceIndex(126));
        assertArrayEquals(new int[]{10000, 10001, 10002}, Web.portsForServiceIndex(100));
    }

    /**
     * Regression test: mode:socks entries have no target (null), which used to
     * crash /list -- grouping tunnels by target into a TreeMap (which rejects
     * null keys) threw an uncaught NullPointerException, and the JDK HttpServer's
     * default handling of a handler throwing is to close the connection with no
     * response at all ("empty reply from server" client-side, not even a 500).
     * Reproduced against a real running Web/HttpServer instance since the bug
     * lived in wiring, not in any single pure/testable method.
     */
    @Test
    void listHandlesModeSocksEntriesWithoutTarget() throws Exception {
        var socksTunnel = new Tunnel("prod", "bhg-prod", null, "default", 9990, null, true, null, null,
                Tunnel.Mode.SOCKS, null, null, null);
        var serviceTunnel = tunnel("prod", "bhg-prod", "service/play-api-public", 10000);
        var config = new Configuration(List.of("prod"), new java.util.ArrayList<>(List.of(socksTunnel, serviceTunnel)),
                Duration.ofSeconds(60), Duration.ofSeconds(60), 58391, false, Optional.empty());

        var web = new Web(config);
        web.start();
        try {
            var client = java.net.http.HttpClient.newHttpClient();
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:58391/list")).GET().build();
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertFalse(response.body().contains("Internal error"));
            assertTrue(response.body().contains("play-api-public"));
        } finally {
            web.stop(0);
        }
    }

    /**
     * Regression test: the edit-tunnel form's "Connection" dropdown has
     * {@code value="socks"} for its SOCKS option, but the submit handler used
     * to check for {@code "service"} instead -- selecting SOCKS in the form
     * silently applied a direct port-forward every time. Both the dropdown's
     * value and the parsing now go through the same {@link
     * Web#isSocksModeParam}.
     */
    @Test
    void isSocksModeParamMatchesTheFormDropdownsActualValue() {
        assertTrue(Web.isSocksModeParam("socks"));
        assertTrue(Web.isSocksModeParam("SOCKS"));
        assertFalse(Web.isSocksModeParam("service"));
        assertFalse(Web.isSocksModeParam("port-forward"));
        assertFalse(Web.isSocksModeParam(null));
    }
}
