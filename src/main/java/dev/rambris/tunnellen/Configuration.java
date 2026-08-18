package dev.rambris.tunnellen;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * @param socksPodSuffix Optional override identifying this developer for cluster
 *                       SOCKS pod names ({@code tunnellen-socks-<suffix>}), so
 *                       multiple developers sharing a cluster don't collide on
 *                       the same pod name. Falls back to the {@code user.name}
 *                       system property when absent -- see
 *                       {@link Main#resolveSocksPodSuffix(Configuration)}.
 */
public record Configuration(List<String> groups, List<Tunnel> portForwards, Duration keepAliveInterval, Duration refreshInterval, int port, boolean killProc, Optional<String> socksPodSuffix) {

    public Configuration withKeepAliveInterval(Duration keepAliveInterval) {
        return new Configuration(groups, portForwards, keepAliveInterval, refreshInterval, port, killProc, socksPodSuffix);
    }

    public Configuration withRefreshInterval(Duration refreshInterval) {
        return new Configuration(groups, portForwards, keepAliveInterval, refreshInterval, port, killProc, socksPodSuffix);
    }

    public Configuration withPort(int port) {
        return new Configuration(groups, portForwards, keepAliveInterval, refreshInterval, port, killProc, socksPodSuffix);
    }

    public Configuration withGroups(List<String> groups) {
        return new Configuration(groups, portForwards, keepAliveInterval, refreshInterval, port, killProc, socksPodSuffix);
    }
}
