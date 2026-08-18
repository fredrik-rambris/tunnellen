package dev.rambris.tunnellen;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BIG-REFACTOR.md 2.1 verification: proves the JDK's built-in SOCKS5
 * username/password auth client actually works end-to-end for {@link
 * SocksServiceTunnel}, without needing a real Kubernetes cluster.
 *
 * <p>Sets up, entirely locally:
 * <ul>
 *   <li>a plain TCP echo server standing in for the "final destination"
 *       Kubernetes service;</li>
 *   <li>a minimal, test-only SOCKS5 server stub ({@link FakeSocks5Server})
 *       that performs the RFC 1928 method negotiation + RFC 1929
 *       username/password handshake and the CONNECT command, then just
 *       relays bytes to the echo server;</li>
 *   <li>a {@link ClusterSocksTunnel}-shaped stand-in (we don't run a real
 *       one — {@link SocksServiceTunnel} only needs its local port/username/
 *       password) pointed at the fake SOCKS5 server;</li>
 *   <li>a real {@link SocksServiceTunnel} wired to that stand-in.</li>
 * </ul>
 * Then connects to the tunnel's local {@code ServerSocket}, writes bytes,
 * and asserts they round-trip through the fake SOCKS5 server to the echo
 * server and back unchanged.
 */
class SocksServiceTunnelTest {

    private EchoServer echoServer;
    private FakeSocks5Server socksServer;
    private ClusterSocksTunnel clusterTunnel;
    private SocksServiceTunnel serviceTunnel;

    @AfterEach
    void tearDown() throws Exception {
        if (serviceTunnel != null) serviceTunnel.stop();
        if (socksServer != null) socksServer.stop();
        if (echoServer != null) echoServer.stop();
    }

    @Test
    void bytesRoundTripThroughFakeAuthenticatedSocks5Proxy() throws Exception {
        echoServer = new EchoServer();
        echoServer.start();

        socksServer = new FakeSocks5Server("tunneluser", "tunnelpass", echoServer.getPort());
        socksServer.start();

        // Stand-in ClusterSocksTunnel pointed at the fake SOCKS5 server, using
        // matching test credentials. SocksServiceTunnel only relies on
        // getLocalPort()/getUsername()/getPassword() from this object.
        clusterTunnel = new ClusterSocksTunnel("fake-context", socksServer.getPort(), "tunneluser", "tunnelpass");

        int localPort = findFreePort();
        // "target"/"namespace" are irrelevant to the fake proxy (it always
        // connects onward to the echo server), but exercise the same code
        // path a real service tunnel would use.
        serviceTunnel = new SocksServiceTunnel("fake-context", "service/whatever", "default", localPort, "9999", clusterTunnel);
        serviceTunnel.start();

        // Give the accept loop a beat to bind/start.
        waitUntil(serviceTunnel::isRunning, 5000);

        byte[] payload = "hello through socks5\n".getBytes(StandardCharsets.UTF_8);
        try (var client = new Socket(InetAddress.getLoopbackAddress(), localPort)) {
            client.setSoTimeout(5000);
            client.getOutputStream().write(payload);
            client.getOutputStream().flush();

            byte[] received = readExactly(client.getInputStream(), payload.length);
            assertArrayEquals(payload, received, "bytes should round-trip unchanged through the fake SOCKS5 proxy");
        }

        assertTrue(socksServer.sawSuccessfulAuth(), "fake SOCKS5 server should have observed a successful username/password auth");
    }

    private static int findFreePort() throws IOException {
        try (var s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condition not met within timeout");
            }
            Thread.sleep(20);
        }
    }

    private static byte[] readExactly(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new IOException("stream closed early after " + off + " of " + n + " bytes");
            off += r;
        }
        return buf;
    }

    /** Test-only plain TCP echo server: writes back whatever it reads. */
    private static class EchoServer {
        private ServerSocket server;
        private Thread thread;
        private final List<Socket> clients = new ArrayList<>();

        void start() throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            thread = new Thread(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket s = server.accept();
                        synchronized (clients) {
                            clients.add(s);
                        }
                        new Thread(() -> echo(s)).start();
                    } catch (IOException e) {
                        return;
                    }
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        private void echo(Socket s) {
            try {
                s.getInputStream().transferTo(s.getOutputStream());
            } catch (IOException ignored) {
            } finally {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        }

        int getPort() {
            return server.getLocalPort();
        }

        void stop() throws IOException {
            server.close();
            synchronized (clients) {
                for (Socket s : clients) {
                    try {
                        s.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    /**
     * Minimal test-only SOCKS5 server implementing just enough of RFC
     * 1928 (method negotiation + CONNECT) and RFC 1929 (username/password
     * sub-negotiation) to exercise the JDK client's built-in auth support.
     * Not a general-purpose SOCKS5 implementation - deliberately kept to
     * the bare minimum needed for this test.
     */
    private static class FakeSocks5Server {
        private final String expectedUser;
        private final String expectedPass;
        private final int upstreamPort;
        private ServerSocket server;
        private Thread thread;
        private volatile boolean sawSuccessfulAuth = false;

        FakeSocks5Server(String expectedUser, String expectedPass, int upstreamPort) {
            this.expectedUser = expectedUser;
            this.expectedPass = expectedPass;
            this.upstreamPort = upstreamPort;
        }

        void start() throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            thread = new Thread(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket s = server.accept();
                        new Thread(() -> handle(s)).start();
                    } catch (IOException e) {
                        return;
                    }
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        int getPort() {
            return server.getLocalPort();
        }

        boolean sawSuccessfulAuth() {
            return sawSuccessfulAuth;
        }

        void stop() throws IOException {
            server.close();
        }

        private void handle(Socket client) {
            try (client) {
                InputStream in = client.getInputStream();
                OutputStream out = client.getOutputStream();

                // --- Greeting: VER(1) NMETHODS(1) METHODS(NMETHODS) ---
                int ver = readByte(in);
                if (ver != 0x05) return;
                int nMethods = readByte(in);
                byte[] methods = readN(in, nMethods);
                boolean offersUserPass = false;
                for (byte m : methods) {
                    if (m == 0x02) offersUserPass = true;
                }
                if (!offersUserPass) {
                    out.write(new byte[]{0x05, (byte) 0xFF}); // no acceptable methods
                    out.flush();
                    return;
                }
                out.write(new byte[]{0x05, 0x02}); // select username/password auth
                out.flush();

                // --- Username/password sub-negotiation (RFC 1929) ---
                int subVer = readByte(in);
                int ulen = readByte(in);
                byte[] uBytes = readN(in, ulen);
                int plen = readByte(in);
                byte[] pBytes = readN(in, plen);
                String user = new String(uBytes, StandardCharsets.UTF_8);
                String pass = new String(pBytes, StandardCharsets.UTF_8);
                boolean ok = subVer == 0x01 && expectedUser.equals(user) && expectedPass.equals(pass);
                out.write(new byte[]{0x01, (byte) (ok ? 0x00 : 0x01)});
                out.flush();
                if (!ok) return;
                sawSuccessfulAuth = true;

                // --- CONNECT request: VER CMD RSV ATYP DST.ADDR DST.PORT ---
                int cver = readByte(in);
                int cmd = readByte(in);
                readByte(in); // RSV
                int atyp = readByte(in);
                switch (atyp) {
                    case 0x01 -> readN(in, 4); // IPv4
                    case 0x03 -> {
                        int len = readByte(in);
                        readN(in, len);
                    }
                    case 0x04 -> readN(in, 16); // IPv6
                    default -> {
                        return;
                    }
                }
                readN(in, 2); // DST.PORT (ignored — we always connect upstream)
                if (cver != 0x05 || cmd != 0x01) {
                    out.write(replyBytes(0x07)); // command not supported
                    out.flush();
                    return;
                }

                Socket upstream;
                try {
                    upstream = new Socket(InetAddress.getLoopbackAddress(), upstreamPort);
                } catch (IOException e) {
                    out.write(replyBytes(0x01)); // general failure
                    out.flush();
                    return;
                }

                out.write(replyBytes(0x00)); // succeeded
                out.flush();

                try (upstream) {
                    Thread t1 = new Thread(() -> transferQuietly(client, upstream));
                    Thread t2 = new Thread(() -> transferQuietly(upstream, client));
                    t1.start();
                    t2.start();
                    t1.join(TimeUnit.SECONDS.toMillis(10));
                    t2.join(TimeUnit.SECONDS.toMillis(10));
                }
            } catch (IOException | InterruptedException ignored) {
                if (Thread.currentThread().isInterrupted()) Thread.currentThread().interrupt();
            }
        }

        private void transferQuietly(Socket from, Socket to) {
            try {
                from.getInputStream().transferTo(to.getOutputStream());
            } catch (IOException ignored) {
            } finally {
                try {
                    to.shutdownOutput();
                } catch (IOException ignored) {
                }
            }
        }

        private static byte[] replyBytes(int rep) {
            // VER REP RSV ATYP BND.ADDR(4) BND.PORT(2) — bind addr/port unused by client here
            return new byte[]{0x05, (byte) rep, 0x00, 0x01, 0, 0, 0, 0, 0, 0};
        }

        private static int readByte(InputStream in) throws IOException {
            int b = in.read();
            if (b < 0) throw new IOException("unexpected EOF");
            return b;
        }

        private static byte[] readN(InputStream in, int n) throws IOException {
            byte[] buf = new byte[n];
            int off = 0;
            while (off < n) {
                int r = in.read(buf, off, n - off);
                if (r < 0) throw new IOException("unexpected EOF");
                off += r;
            }
            return buf;
        }
    }
}
