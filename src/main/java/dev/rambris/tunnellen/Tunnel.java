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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class Tunnel implements Comparable<Tunnel> {
    private final Logger log;
    private final String context;
    private final String target;
    private final int localPort;
    private final String destinationPort;
    private final Optional<String> namespace;
    private final boolean startOnStartup;
    private Process proc = null;
    private String id;
    private LocalDateTime lastCheck = LocalDateTime.MIN;
    private AsyncInputStreamReader outputReader;

    public Tunnel(String context, String target, String namespace, int localPort, String destinationPort, boolean startOnStartup) {
        log = (Logger) LoggerFactory.getLogger("tunnel." + context + "." + target + "[" + localPort + ":" + destinationPort + "]");
        this.context = context;
        this.target = target;
        this.namespace = Optional.ofNullable(namespace);
        this.localPort = localPort;
        this.destinationPort = destinationPort;
        this.startOnStartup = startOnStartup;


        try {
            var digest = MessageDigest.getInstance("SHA-256").digest((context + target + namespace + localPort + destinationPort).getBytes());
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
            log.info("Checking tunnel");
            sock.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), localPort), 2000);
            lastCheck = LocalDateTime.now();
            return true;
        } catch (IOException e) {
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

    @Override
    public int compareTo(Tunnel o) {
        return id.compareTo(o.id);
    }
}
