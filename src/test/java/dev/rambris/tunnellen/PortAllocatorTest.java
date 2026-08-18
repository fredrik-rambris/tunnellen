package dev.rambris.tunnellen;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PortAllocatorTest {

    private static Tunnel tunnel(int localPort) {
        return new Tunnel("default", "ctx", "service/foo", "default", localPort, "8080", false, Tunnel.Type.HTTP, null);
    }

    private static Configuration configOf(List<Tunnel> tunnels) {
        return new Configuration(List.of(), tunnels, Duration.ofMinutes(1), Duration.ofMinutes(1), 3000, false, Optional.empty());
    }

    @Test
    void nextServiceIndexReturns100ForEmptyConfig() {
        var config = configOf(List.of());
        assertEquals(100, PortAllocator.nextServiceIndex(config));
    }

    @Test
    void nextServiceIndexIgnoresPortsBelowSchemeThreshold() {
        // Ports below 10000 (e.g. minikube/misc: 1080, 5432, 8001, 8085, 9000) are not
        // part of the SSSEE scheme and must not influence the allocation.
        var config = configOf(List.of(tunnel(1080), tunnel(5432), tunnel(8001), tunnel(8085), tunnel(9000)));
        assertEquals(100, PortAllocator.nextServiceIndex(config));
    }

    @Test
    void nextServiceIndexReturnsMaxPlusOneNotFirstGap() {
        // Intentional: the allocator returns "next free 3-digit block" via max(SSS)+1.
        // It does NOT backfill gaps, so indices {100, 105} in use yield 106, not 101.
        var config = configOf(List.of(
                tunnel(10000), tunnel(10001), tunnel(10002),
                tunnel(10500), tunnel(10501), tunnel(10502)
        ));
        assertEquals(106, PortAllocator.nextServiceIndex(config));
    }

    @Test
    void nextServiceIndexIgnoresEnvironmentSuffixWhenComputingMax() {
        // Only the SSS portion (localPort / 100) matters; the EE suffix (00/01/02) is
        // discarded, so a lone dev-only port at 10502 still yields service index 105.
        var config = configOf(List.of(tunnel(10502)));
        assertEquals(106, PortAllocator.nextServiceIndex(config));
    }

    @Test
    void nextServiceIndexAgainstRealNewForwardsFixture() throws Exception {
        // Was reading the developer's own untracked new-forwards.yaml from the
        // working directory -- fine locally, but ConfigurationRepository#loadConfig
        // calls System.exit(1) when the file is missing, which killed the whole
        // forked test JVM on CI (no such file there). Use a checked-in fixture
        // with the same shape instead.
        var file = new File("src/test/resources/fixtures/high-index-forwards.yaml");
        var config = ConfigurationRepository.loadConfig(file, 3000);
        // Highest index in the fixture is 125 (bokus-reader-service-public at
        // 12500/12501/12502), so the next free allocation is 126.
        assertEquals(126, PortAllocator.nextServiceIndex(config));
    }

    @Test
    void portsForReturnsProdTestDevTriad() {
        var ports = PortAllocator.portsFor(126);
        assertEquals(12600, ports.prod());
        assertEquals(12601, ports.test());
        assertEquals(12602, ports.dev());
    }

    @Test
    void portsForBaseIndex100() {
        var ports = PortAllocator.portsFor(100);
        assertEquals(10000, ports.prod());
        assertEquals(10001, ports.test());
        assertEquals(10002, ports.dev());
    }
}
