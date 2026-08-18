package dev.rambris.tunnellen;

import ch.qos.logback.classic.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class Tunnel implements Comparable<Tunnel>, TunnelRunner {
    public enum Type {
        HTTP,
        DATABASE
    }

    /**
     * Orthogonal to {@link Type}. {@code Type} is purely a presentation hint for the
     * webUI (which icon/link to show) and has no bearing on transport. {@code Mode}
     * describes how the tunnel is actually transported:
     * <ul>
     *   <li>{@link #PORT_FORWARD} — a direct {@code kubectl port-forward} per tunnel
     *       (the default/legacy behavior).</li>
     *   <li>{@link #SOCKS} — the cluster tunnel itself: a single shared per-cluster
     *       SOCKS5 proxy (see {@link ClusterSocksTunnel}). No {@code target}/
     *       {@code localPort} of its own is required at the service level.</li>
     *   <li>{@link #SERVICE} — a service tunnel that rides on a {@link #SOCKS}
     *       cluster tunnel, referenced via {@link #getDependsOn()}.</li>
     * </ul>
     */
    public enum Mode {
        PORT_FORWARD,
        SOCKS,
        SERVICE
    }

    private final Logger log;
    private final String group;
    private final String context;
    private final String target;
    private final int localPort;
    private final String destinationPort;
    private final Optional<String> namespace;
    private final Optional<Type> type;
    private final Database database;
    private final boolean startOnStartup;
    private final Mode mode;
    private final Optional<String> dependsOn;
    private final Optional<String> socksUsername;
    private final Optional<String> socksPassword;
    private Process proc = null;
    private String id;
    private LocalDateTime lastCheck = LocalDateTime.MIN;
    private AsyncInputStreamReader outputReader;

    public Tunnel(String group, String context, String target, String namespace, int localPort, String destinationPort, boolean startOnStartup, Type type, Database database) {
        this(group, context, target, namespace, localPort, destinationPort, startOnStartup, type, database, Mode.PORT_FORWARD, null);
    }

    public Tunnel(String group, String context, String target, String namespace, int localPort, String destinationPort, boolean startOnStartup, Type type, Database database, Mode mode, String dependsOn) {
        this(group, context, target, namespace, localPort, destinationPort, startOnStartup, type, database, mode, dependsOn, null, null);
    }

    public Tunnel(String group, String context, String target, String namespace, int localPort, String destinationPort, boolean startOnStartup, Type type, Database database, Mode mode, String dependsOn, String socksUsername, String socksPassword) {
        log = (Logger) LoggerFactory.getLogger("tunnel." + context + "." + target + "[" + localPort + ":" + destinationPort + "]");
        this.group = group;
        this.context = context;
        this.target = target;
        this.namespace = Optional.ofNullable(namespace);
        this.type = Optional.ofNullable(type);
        this.localPort = localPort;
        this.destinationPort = destinationPort;
        this.startOnStartup = startOnStartup;
        this.database = database;
        this.mode = mode != null ? mode : Mode.PORT_FORWARD;
        this.dependsOn = Optional.ofNullable(dependsOn);
        this.socksUsername = Optional.ofNullable(socksUsername);
        this.socksPassword = Optional.ofNullable(socksPassword);

        try {
            var digest = MessageDigest.getInstance("SHA-256").digest((group + context + target + namespace + localPort + destinationPort).getBytes());
            id = UUID.nameUUIDFromBytes(digest).toString();
        } catch (NoSuchAlgorithmException e) {
            log.warn("Could not create UUID from SHA-256, using random UUID instead. {}", e.getMessage());
            id = UUID.randomUUID().toString();
        }
    }

    public void start() {
        var cmd = new String[]{
                "kubectl",
                "--context=" + context,
                "port-forward",
                "--address", "0.0.0.0",
                "--namespace", namespace.orElse("default"),
                target,
                localPort + ":" + destinationPort
        };
        try {
            proc = new ProcessBuilder(cmd).start();
            log.info("Started tunnel");
            outputReader = new AsyncInputStreamReader(proc.getInputStream(), this::handleOutput);
        } catch (IOException e) {
            System.err.println(e.getMessage());
        }
    }

    private void handleOutput(String line) {
        log.info(line);
    }

    public boolean isRunning() {
        return proc != null && proc.isAlive();
    }

    public boolean isStarted() {
        return proc != null;
    }

    public boolean isAlive() {
        try (var sock = new Socket()) {
            log.debug("Checking tunnel");
            sock.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), localPort), 2000);
            lastCheck = LocalDateTime.now();
            log.debug("Tunnel is alive");
            return true;
        } catch (IOException e) {
            log.debug("Tunnel is not alive: {}", e.getMessage());
            return false;
        }
    }

    public void stop() {
        if (proc != null && proc.isAlive()) {
            log.info("Stopping tunnel");
            proc.destroy();
            outputReader.stop();
            try {
                proc.waitFor(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            log.info("Tunnel stopped");
        }
        proc = null;
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

    public int getLocalPort() {
        return localPort;
    }

    public String getDestinationPort() {
        return destinationPort;
    }

    public String getNamespace() {
        return namespace.orElse(null);
    }

    public boolean isStartOnStartup() {
        return startOnStartup;
    }

    public LocalDateTime getLastCheck() {
        return lastCheck;
    }

    public Optional<Type> getType() {
        return type;
    }

    public Database getDatabase() {
        return database;
    }

    public String getGroup() {
        return group;
    }

    public Mode getMode() {
        return mode;
    }

    public Optional<String> getDependsOn() {
        return dependsOn;
    }

    /**
     * Optional static SOCKS username override for {@code mode: socks} cluster
     * tunnels (see BIG-REFACTOR.md 1.1a/2.3). Absent means "generate randomly",
     * consumed later by whatever wires this {@code Tunnel} into a
     * {@link ClusterSocksTunnel}.
     */
    public Optional<String> getSocksUsername() {
        return socksUsername;
    }

    /**
     * Optional static SOCKS password override, paired with {@link #getSocksUsername()}.
     */
    public Optional<String> getSocksPassword() {
        return socksPassword;
    }

    @Override
    public int compareTo(Tunnel o) {
        return id.compareTo(o.id);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Tunnel tunnel)) return false;
        return Objects.equals(id, tunnel.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Tunnel{" +
                "context='" + context + '\'' +
                ", target='" + target + '\'' +
                ", namespace=" + namespace +
                ", localPort=" + localPort +
                ", destinationPort='" + destinationPort + '\'' +
                ", mode=" + mode +
                ", dependsOn=" + dependsOn +
                ", socksUsername=" + socksUsername +
                '}';
    }
}
