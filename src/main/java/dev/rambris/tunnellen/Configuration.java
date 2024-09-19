package dev.rambris.tunnellen;

import java.time.Duration;
import java.util.List;

public record Configuration(List<String> groups, List<Tunnel> portForwards, Duration keepAliveInterval, Duration refreshInterval, int port) {

    public Configuration withKeepAliveInterval(Duration keepAliveInterval) {
        return new Configuration(groups, portForwards, keepAliveInterval, refreshInterval, port);
    }

    public Configuration withRefreshInterval(Duration refreshInterval) {
        return new Configuration(groups, portForwards, keepAliveInterval, refreshInterval, port);
    }

    public Configuration withPort(int port) {
        return new Configuration(groups, portForwards, keepAliveInterval, refreshInterval, port);
    }

    public Configuration withGroups(List<String> groups) {
        return new Configuration(groups, portForwards, keepAliveInterval, refreshInterval, port);
    }
}
