package dev.rambris.tunnellen;

import ch.qos.logback.classic.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.stream.Collectors;

public class KeepAlive {
    private static final Logger log = (Logger) LoggerFactory.getLogger(KeepAlive.class);
    private List<TunnelRunner> tunnels = new ArrayList<>();
    private List<ClusterSocksTunnel> clusterTunnels = new ArrayList<>();
    private boolean running = false;
    private Timer timer;
    private Duration keepAliveInterval;

    public KeepAlive(Duration keepAliveInterval) {
        this.keepAliveInterval = keepAliveInterval;
    }

    /**
     * BIG-REFACTOR.md step 2.3a: accepts any {@link TunnelRunner} — a plain
     * {@link Tunnel} (direct {@code kubectl port-forward}) or a {@link
     * SocksServiceTunnel} (rides on a {@link ClusterSocksTunnel}) — so both
     * kinds get uniform health-check/restart treatment.
     */
    public void addTunnel(TunnelRunner tunnel) {
        var newTunnels = new ArrayList<>(tunnels);
        newTunnels.add(tunnel);
        tunnels = newTunnels;
    }

    public void removeTunnel(TunnelRunner tunnel) {
        var newTunnels = new ArrayList<>(tunnels);
        newTunnels.remove(tunnel);
        tunnels = newTunnels;
    }

    /**
     * Registers a {@link ClusterSocksTunnel} to be monitored/restarted
     * alongside regular {@link Tunnel}s (BIG-REFACTOR.md step 1.4).
     */
    public void addClusterTunnel(ClusterSocksTunnel tunnel) {
        var newTunnels = new ArrayList<>(clusterTunnels);
        newTunnels.add(tunnel);
        clusterTunnels = newTunnels;
    }

    public void removeClusterTunnel(ClusterSocksTunnel tunnel) {
        var newTunnels = new ArrayList<>(clusterTunnels);
        newTunnels.remove(tunnel);
        clusterTunnels = newTunnels;
    }

    /**
     * Pure decision logic for BIG-REFACTOR.md step 2.2: given the set of
     * cluster context names currently detected down, and the {@code
     * dependsOn} value of each candidate dependent (may be {@code null} for
     * tunnels that don't ride on any cluster tunnel), returns the subset of
     * {@code dependsOnValues} whose restart attempt should be skipped this
     * check cycle because the cluster tunnel they depend on is down.
     *
     * <p>Deliberately takes/returns plain {@code String}s rather than
     * {@code Tunnel}/{@code ClusterSocksTunnel} instances so it's unit
     * testable in isolation, independent of any concrete tunnel
     * implementation (in particular, independent of {@code
     * SocksServiceTunnel}, which lands in a separate, concurrently-developed
     * step).
     */
    static Set<String> skippedDependents(Set<String> downContexts, List<String> dependsOnValues) {
        if (downContexts.isEmpty() || dependsOnValues.isEmpty()) {
            return Collections.emptySet();
        }
        return dependsOnValues.stream()
                .filter(java.util.Objects::nonNull)
                .filter(downContexts::contains)
                .collect(Collectors.toSet());
    }

    /**
     * Whether a single dependent (identified by its {@code dependsOn}
     * value, {@code null}/absent for tunnels not riding on a cluster
     * tunnel) should have its restart attempt skipped this cycle because
     * the cluster tunnel it depends on was found down this cycle.
     */
    static boolean isWaitingOnClusterTunnel(String dependsOn, Set<String> downContexts) {
        return dependsOn != null && downContexts.contains(dependsOn);
    }

    /**
     * Checks and (if needed) restarts every registered {@link
     * ClusterSocksTunnel}, once per tunnel per cycle. Returns the set of
     * context names that were found down (i.e. {@code !isAlive()}) at the
     * start of this check, *before* the restart attempt — used to hold
     * dependent tunnels back this cycle (2.2) rather than restart-storming
     * them alongside a cluster tunnel that isn't up yet.
     */
    private Set<String> checkClusterTunnels() {
        Set<String> downContexts = new HashSet<>();
        clusterTunnels.forEach(ct -> {
            log.debug("Checking cluster SOCKS tunnel {}", ct.getContext());
            if (!ct.isAlive()) {
                downContexts.add(ct.getContext());
                log.info("Restarting cluster SOCKS tunnel {}", ct.getContext());
                ct.stop();
                ct.start();
            }
        });
        return downContexts;
    }

    private void checkTunnels() {
        if (!running) {
            return;
        }
        var threshold = LocalDateTime.now().minus(keepAliveInterval);

        // 2.2: cluster tunnels are checked/restarted first each cycle, so we
        // know which contexts are down before deciding whether to attempt
        // restarts on their dependents.
        var downContexts = checkClusterTunnels();

        tunnels.stream()
                .filter(e -> e.getLastCheck().isBefore(threshold))
                .forEach(t -> {
                    var dependsOn = t.getDependsOn().orElse(null);
                    if (isWaitingOnClusterTunnel(dependsOn, downContexts)) {
                        log.debug("Tunnel {}:{} is waiting on cluster tunnel {} (down this cycle), skipping restart attempt", t.getContext(), t.getTarget(), dependsOn);
                        return;
                    }
                    log.debug("Checking tunnel {}:{} (started:{})", t.getContext(), t.getTarget(), t.isStarted());
                    if (t.isStarted() && !t.isAlive()) {
                        log.info("Restarting tunnel");
                        t.stop();
                        t.start();
                    }
                });
    }

    public void start() {
        if (!running) {
            timer = new Timer("Keepalive");
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    checkTunnels();
                }
            }, keepAliveInterval.toMillis(), keepAliveInterval.toMillis());

            running = true;
        }
    }

    public void stop() {
        if (running) {
            running = false;
            timer.cancel();
            timer = null;
        }
    }

    public void setKeepAliveInterval(Duration keepAliveInterval) {
        this.keepAliveInterval = keepAliveInterval;
        if (running) {
            stop();
            start();
        }
    }
}
