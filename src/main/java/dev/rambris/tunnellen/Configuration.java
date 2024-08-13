package dev.rambris.tunnellen;

import java.time.Duration;
import java.util.List;

public record Configuration(List<Tunnel> portForwards, Duration keepAliveInterval, int port) {

}
