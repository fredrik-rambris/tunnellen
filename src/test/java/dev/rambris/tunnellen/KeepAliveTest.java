package dev.rambris.tunnellen;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure "which dependents get skipped this cycle" logic
 * introduced for BIG-REFACTOR.md step 2.2. Deliberately exercises {@link
 * KeepAlive#skippedDependents(Set, List)} and {@link
 * KeepAlive#isWaitingOnClusterTunnel(String, Set)} directly, without any
 * concrete {@code Tunnel}/{@code ClusterSocksTunnel}/{@code
 * SocksServiceTunnel} instances, since the latter isn't available yet
 * (built concurrently in step 2.1).
 */
class KeepAliveTest {

    @Test
    void noDependentsSkippedWhenNoClusterTunnelIsDown() {
        var downContexts = Set.<String>of();
        var dependsOnValues = List.of("bhg-dev", "bhg-test", "bhg-prod");

        var skipped = KeepAlive.skippedDependents(downContexts, dependsOnValues);

        assertTrue(skipped.isEmpty());
    }

    @Test
    void dependentsOfDownContextAreSkipped() {
        var downContexts = Set.of("bhg-dev");
        var dependsOnValues = List.of("bhg-dev", "bhg-dev", "bhg-test");

        var skipped = KeepAlive.skippedDependents(downContexts, dependsOnValues);

        assertEquals(Set.of("bhg-dev"), skipped);
    }

    @Test
    void onlyDependentsOfDownContextsAreSkippedWhenMultipleContextsExist() {
        var downContexts = Set.of("bhg-dev", "bhg-prod");
        var dependsOnValues = List.of("bhg-dev", "bhg-test", "bhg-prod");

        var skipped = KeepAlive.skippedDependents(downContexts, dependsOnValues);

        assertEquals(Set.of("bhg-dev", "bhg-prod"), skipped);
    }

    @Test
    void nullDependsOnValuesAreIgnored() {
        var downContexts = Set.of("bhg-dev");
        var dependsOnValues = Arrays.asList("bhg-dev", null, "bhg-test");

        var skipped = KeepAlive.skippedDependents(downContexts, dependsOnValues);

        assertEquals(Set.of("bhg-dev"), skipped);
    }

    @Test
    void emptyDependsOnListYieldsNoSkips() {
        var downContexts = Set.of("bhg-dev");

        var skipped = KeepAlive.skippedDependents(downContexts, List.of());

        assertTrue(skipped.isEmpty());
    }

    @Test
    void isWaitingOnClusterTunnelIsFalseForNonDependentTunnel() {
        assertFalse(KeepAlive.isWaitingOnClusterTunnel(null, Set.of("bhg-dev")));
    }

    @Test
    void isWaitingOnClusterTunnelIsFalseWhenItsClusterIsUp() {
        assertFalse(KeepAlive.isWaitingOnClusterTunnel("bhg-test", Set.of("bhg-dev")));
    }

    @Test
    void isWaitingOnClusterTunnelIsTrueWhenItsClusterIsDown() {
        assertTrue(KeepAlive.isWaitingOnClusterTunnel("bhg-dev", Set.of("bhg-dev")));
    }

    @Test
    void addAndRemoveClusterTunnelDoNotThrow() {
        var keepAlive = new KeepAlive(java.time.Duration.ofSeconds(30));
        var clusterTunnel = new ClusterSocksTunnel("bhg-dev", 19000);

        keepAlive.addClusterTunnel(clusterTunnel);
        keepAlive.removeClusterTunnel(clusterTunnel);
        // no exception, no live process required (we never start() it) -- this
        // just exercises the registration bookkeeping added for step 1.4.
    }
}
