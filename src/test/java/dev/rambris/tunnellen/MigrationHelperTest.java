package dev.rambris.tunnellen;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MigrationHelperTest {

    private static Tunnel portForward(String group, String context, String target, int localPort) {
        return new Tunnel(group, context, target, "default", localPort, "8080", true, Tunnel.Type.HTTP, null);
    }

    private static Configuration configOf(Tunnel... tunnels) {
        return new Configuration(List.of("prod", "test", "dev"), new ArrayList<>(List.of(tunnels)),
                Duration.ofSeconds(60), Duration.ofSeconds(60), 3000, false, Optional.empty());
    }

    @Test
    void createsClusterTunnelWhenContextHasNoneYet() {
        var config = configOf(portForward("prod", "bhg-prod", "service/foo", 10000));
        var plan = MigrationHelper.plan(config, Set.of("bhg-prod"), List.of());

        assertEquals(1, plan.newClusterTunnels().size());
        var clusterTunnel = plan.newClusterTunnels().get(0);
        assertEquals("bhg-prod", clusterTunnel.getContext());
        assertEquals(Tunnel.Mode.SOCKS, clusterTunnel.getMode());
        assertEquals("prod", clusterTunnel.getGroup());
    }

    @Test
    void skipsCreatingClusterTunnelWhenOneAlreadyExists() {
        var existing = new Tunnel("prod", "bhg-prod", null, null, 0, null, true, null, null,
                Tunnel.Mode.SOCKS, null, null, null);
        var config = configOf(existing, portForward("prod", "bhg-prod", "service/foo", 10000));
        var plan = MigrationHelper.plan(config, Set.of("bhg-prod"), List.of());

        assertTrue(plan.newClusterTunnels().isEmpty());
    }

    @Test
    void convertsAllPortForwardTunnelsInSelectedContextWhenNoFilterGiven() {
        var config = configOf(
                portForward("prod", "bhg-prod", "service/foo", 10000),
                portForward("prod", "bhg-prod", "service/bar", 10001),
                portForward("test", "bhg-test", "service/foo", 11000));
        var plan = MigrationHelper.plan(config, Set.of("bhg-prod"), List.of());

        assertEquals(2, plan.toConvert().size());
        assertTrue(plan.skipped().isEmpty());
        plan.toConvert().forEach(c -> {
            assertEquals(Tunnel.Mode.SERVICE, c.after().getMode());
            assertEquals("bhg-prod", c.after().getDependsOn().orElseThrow());
            // Everything else about the tunnel is preserved.
            assertEquals(c.before().getTarget(), c.after().getTarget());
            assertEquals(c.before().getLocalPort(), c.after().getLocalPort());
            assertEquals(c.before().getGroup(), c.after().getGroup());
        });
    }

    @Test
    void serviceFilterSelectsOnlyAStrictSubset() {
        var config = configOf(
                portForward("prod", "bhg-prod", "service/play-api-public", 10000),
                portForward("prod", "bhg-prod", "service/play-audit-service", 10001),
                portForward("prod", "bhg-prod", "service/campaigntool", 10002));
        var plan = MigrationHelper.plan(config, Set.of("bhg-prod"), List.of("play-api"));

        assertEquals(1, plan.toConvert().size());
        assertEquals("service/play-api-public", plan.toConvert().get(0).before().getTarget());
        assertEquals(2, plan.skipped().size());
    }

    @Test
    void leavesAlreadyMigratedTunnelsAlone() {
        var already = new Tunnel("prod", "bhg-prod", "service/foo", "default", 10000, "8080", true,
                Tunnel.Type.HTTP, null, Tunnel.Mode.SERVICE, "bhg-prod", null, null);
        var config = configOf(already);
        var plan = MigrationHelper.plan(config, Set.of("bhg-prod"), List.of());

        assertTrue(plan.toConvert().isEmpty());
        assertTrue(plan.skipped().isEmpty());
    }

    @Test
    void emptyPlanWhenNothingMatches() {
        // Cluster tunnel already exists for bhg-prod (so newClusterTunnels stays
        // empty too) and the service filter matches nothing -- a genuinely empty
        // plan, not just an empty toConvert list.
        var existingClusterTunnel = new Tunnel("prod", "bhg-prod", null, null, 0, null, true, null, null,
                Tunnel.Mode.SOCKS, null, null, null);
        var config = configOf(existingClusterTunnel, portForward("prod", "bhg-prod", "service/foo", 10000));
        var plan = MigrationHelper.plan(config, Set.of("bhg-prod"), List.of("nonexistent"));

        assertTrue(plan.isEmpty());
        assertTrue(plan.toConvert().isEmpty());
        assertEquals(1, plan.skipped().size());
        assertTrue(MigrationHelper.describe(plan).toLowerCase().contains("nothing matched"));
    }

    @Test
    void unrelatedContextsAreUntouched() {
        var config = configOf(
                portForward("prod", "bhg-prod", "service/foo", 10000),
                portForward("dev", "bhg-dev", "service/foo", 10002));
        var plan = MigrationHelper.plan(config, Set.of("bhg-prod"), List.of());

        assertEquals(1, plan.toConvert().size());
        assertEquals("bhg-prod", plan.toConvert().get(0).before().getContext());
    }
}
