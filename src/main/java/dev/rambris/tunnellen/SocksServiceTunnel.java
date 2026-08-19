package dev.rambris.tunnellen;

import ch.qos.logback.classic.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service tunnel (BIG-REFACTOR.md 2.1) that relays local connections through
 * a shared per-cluster {@link ClusterSocksTunnel} SOCKS5 proxy to a single
 * Kubernetes {@code Service}, instead of running its own {@code kubectl
 * port-forward} process like {@link Tunnel} does.
 *
 * <p>{@link #start()} opens a local {@link ServerSocket} on {@code
 * localPort}. Each accepted connection opens a {@link Socket} through {@code
 * new Proxy(Proxy.Type.SOCKS, ...)} pointed at the owning {@link
 * ClusterSocksTunnel}'s {@code 127.0.0.1:<localPort>}, connecting onward to
 * {@code <target>.<namespace>.svc.cluster.local:<remotePort>}, and pipes
 * bytes bidirectionally between the two sockets on a pair of virtual
 * threads. {@link #stop()} closes the {@code ServerSocket} and any live
 * relayed connections.
 *
 * <h2>SOCKS5 username/password auth</h2>
 * Handled entirely by the JDK's built-in SOCKS client (no hand-rolled
 * protocol code, no third-party library) via a single process-wide {@link
 * java.net.Authenticator} — see {@link SocksCredentialAuthenticator} for the
 * full design rationale and its caveats. In short: this class registers its
 * owning {@link ClusterSocksTunnel}'s credentials into that shared
 * authenticator (keyed by the cluster tunnel's local port) before accepting
 * any connections, so a proxy auth challenge for that port is answered with
 * the right username/password even though multiple clusters may have
 * different credentials and the authenticator itself is JVM-global state.
 */
public class SocksServiceTunnel implements TunnelRunner {
    private final Logger log;
    private final String context;
    private final String target;
    private final String namespace;
    private final int localPort;
    private final String remotePort;
    private final ClusterSocksTunnel clusterTunnel;
    private final String id;

    private final Set<Socket> liveSockets = ConcurrentHashMap.newKeySet();
    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;
    private LocalDateTime lastCheck = LocalDateTime.MIN;

    public SocksServiceTunnel(String context, String target, String namespace, int localPort, String remotePort, ClusterSocksTunnel clusterTunnel) {
        this.log = (Logger) LoggerFactory.getLogger("sockstunnel." + context + "." + target + "[" + localPort + ":" + remotePort + "]");
        this.context = context;
        this.target = target;
        this.namespace = namespace != null ? namespace : "default";
        this.localPort = localPort;
        this.remotePort = remotePort;
        this.clusterTunnel = clusterTunnel;

        String id;
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest((context + target + namespace + localPort + remotePort).getBytes());
            id = UUID.nameUUIDFromBytes(digest).toString();
        } catch (NoSuchAlgorithmException e) {
            log.warn("Could not create UUID from SHA-256, using random UUID instead. {}", e.getMessage());
            id = UUID.randomUUID().toString();
        }
        this.id = id;

        SocksCredentialAuthenticator.installOnce();
    }

    public void start() {
        SocksCredentialAuthenticator.register(clusterTunnel);
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress((InetAddress)null, localPort));
            log.info("Started SOCKS service tunnel on local port {}", localPort);
        } catch (IOException e) {
            log.warn("Failed to open local server socket: {}", e.getMessage());
            serverSocket = null;
            return;
        }

        acceptThread = Thread.ofVirtual().name("socks-accept-" + localPort).start(this::acceptLoop);
    }

    private void acceptLoop() {
        var srv = serverSocket;
        while (srv != null && !srv.isClosed()) {
            Socket client;
            try {
                client = srv.accept();
            } catch (IOException e) {
                if (srv.isClosed()) {
                    return;
                }
                log.debug("Accept failed: {}", e.getMessage());
                continue;
            }
            liveSockets.add(client);
            Thread.ofVirtual().name("socks-relay-accept-" + localPort).start(() -> handleConnection(client));
        }
    }

    private void handleConnection(Socket client) {
        Socket remote = null;
        try {
            var proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(InetAddress.getLoopbackAddress(), clusterTunnel.getLocalPort()));
            remote = new Socket(proxy);
            String remoteHost = bareTarget() + "." + namespace + ".svc.cluster.local";
            remote.connect(new InetSocketAddress(remoteHost, Integer.parseInt(remotePort)), 10000);
            liveSockets.add(remote);
            log.debug("Relaying connection to {}:{}", remoteHost, remotePort);

            var toRemote = remote;
            var relayIn = Thread.ofVirtual().start(() -> pipe(client, toRemote));
            var relayOut = Thread.ofVirtual().start(() -> pipe(toRemote, client));
            relayIn.join();
            relayOut.join();
        } catch (IOException e) {
            log.debug("Relay connection failed: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeQuietly(client);
            if (remote != null) {
                closeQuietly(remote);
            }
        }
    }

    /**
     * {@code target} may carry a kubectl kind prefix (e.g. {@code
     * "service/play-esales-search"}, as used by {@link Tunnel}) — the k8s
     * in-cluster DNS name needs just the bare service name.
     */
    private String bareTarget() {
        int slash = target.indexOf('/');
        return slash >= 0 ? target.substring(slash + 1) : target;
    }

    private void pipe(Socket from, Socket to) {
        try {
            from.getInputStream().transferTo(to.getOutputStream());
        } catch (IOException e) {
            log.debug("Relay stream closed: {}", e.getMessage());
        } finally {
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    private void closeQuietly(Socket s) {
        liveSockets.remove(s);
        try {
            if (!s.isClosed()) {
                s.close();
            }
        } catch (IOException ignored) {
        }
    }

    public boolean isRunning() {
        return serverSocket != null && !serverSocket.isClosed();
    }

    public boolean isStarted() {
        return serverSocket != null;
    }

    public boolean isAlive() {
        try (var sock = new Socket()) {
            log.debug("Checking SOCKS service tunnel");
            sock.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), localPort), 2000);
            lastCheck = LocalDateTime.now();
            log.debug("SOCKS service tunnel is alive");
            return true;
        } catch (IOException e) {
            log.debug("SOCKS service tunnel is not alive: {}", e.getMessage());
            return false;
        }
    }

    public void stop() {
        log.info("Stopping SOCKS service tunnel");
        var srv = serverSocket;
        serverSocket = null;
        if (srv != null) {
            try {
                srv.close();
            } catch (IOException ignored) {
            }
        }
        for (var s : Set.copyOf(liveSockets)) {
            closeQuietly(s);
        }
        var t = acceptThread;
        if (t != null) {
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        acceptThread = null;
        log.info("SOCKS service tunnel stopped");
    }

    public String getId() {
        return id;
    }

    public String getContext() {
        return context;
    }

    public String getTarget() {
        return target;
    }

    public String getNamespace() {
        return namespace;
    }

    public int getLocalPort() {
        return localPort;
    }

    public String getRemotePort() {
        return remotePort;
    }

    public ClusterSocksTunnel getClusterTunnel() {
        return clusterTunnel;
    }

    /**
     * Number of client connections currently being relayed through this tunnel.
     * {@link #liveSockets} holds both sides of each relay (the accepted client
     * socket and the outbound SOCKS-proxied socket to the target), so this halves
     * that count -- briefly undercounts a connection still being established
     * (client added, remote not yet), which is an acceptable approximation for a
     * live status display.
     */
    public int getActiveConnectionCount() {
        return liveSockets.size() / 2;
    }

    public LocalDateTime getLastCheck() {
        return lastCheck;
    }

    /**
     * BIG-REFACTOR.md step 2.3a: satisfies {@link TunnelRunner#getDependsOn()}
     * by reporting the context of the {@link ClusterSocksTunnel} this
     * service tunnel rides on, so {@code KeepAlive}'s dependency-skip logic
     * (2.2) applies uniformly to both {@link Tunnel} and this class.
     */
    public Optional<String> getDependsOn() {
        return Optional.of(clusterTunnel.getContext());
    }
}
