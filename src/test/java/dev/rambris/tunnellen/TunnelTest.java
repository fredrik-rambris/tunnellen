package dev.rambris.tunnellen;

import org.junit.jupiter.api.Test;

import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertFalse;

class TunnelTest {

    private Tunnel newTunnel(int localPort) {
        return new Tunnel("default", "my-context", "service/my-service", "default",
                localPort, "8080", false, Tunnel.Type.HTTP, null);
    }

    @Test
    void isStartedIsFalseBeforeStart() {
        var tunnel = newTunnel(19999);

        assertFalse(tunnel.isStarted(), "a freshly constructed tunnel has never been started");
    }

    @Test
    void isRunningIsFalseWhenNoProcess() {
        var tunnel = newTunnel(19998);

        assertFalse(tunnel.isRunning(), "isRunning() should be false when proc is null");
    }

    @Test
    void isAliveIsFalseWhenNothingListensOnThePort() {
        // isAlive() opens a real TCP socket to localPort; without a live kubectl
        // port-forward process we simply pick a port nothing is listening on and
        // confirm the negative case, since faking a real listener would require
        // starting a subprocess.
        var tunnel = newTunnel(19997);

        assertFalse(tunnel.isAlive());
    }

    @Test
    void isAliveIsTrueWhenSomethingListensOnThePort() throws Exception {
        try (var server = new ServerSocket(0)) {
            var tunnel = newTunnel(server.getLocalPort());

            org.junit.jupiter.api.Assertions.assertTrue(tunnel.isAlive());
        }
    }

    @Test
    void stopResetsStateEvenWhenNeverStarted() {
        var tunnel = newTunnel(19996);

        // stop() on a tunnel that was never start()ed should be a safe no-op
        // and leave it in the not-started state (no real process/socket needed).
        tunnel.stop();

        assertFalse(tunnel.isStarted());
        assertFalse(tunnel.isRunning());
    }
}
