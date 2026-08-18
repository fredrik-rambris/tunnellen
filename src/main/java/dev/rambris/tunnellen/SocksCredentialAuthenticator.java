package dev.rambris.tunnellen;

import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide {@link Authenticator} that answers SOCKS5 username/password
 * auth challenges (RFC 1929) for {@link SocksServiceTunnel} connections
 * (BIG-REFACTOR.md 2.1).
 *
 * <h2>Design</h2>
 * {@link Authenticator#setDefault(Authenticator)} installs a single,
 * <b>JVM-wide</b> default authenticator — there is no per-{@link
 * java.net.Proxy} or per-connection scoping in the JDK's SOCKS client.
 * Since multiple {@link ClusterSocksTunnel}s (one per Kubernetes context) can
 * each have independent, randomly generated credentials, a single {@code
 * Authenticator} instance can't just hold one fixed username/password — it
 * needs to disambiguate *which* cluster tunnel's proxy is being
 * authenticated to on every callback.
 *
 * <p>The JDK's SOCKS client invokes {@link
 * Authenticator#getPasswordAuthentication()} with {@link
 * Authenticator#getRequestingHost()}/{@link Authenticator#getRequestingPort()}
 * set to the SOCKS proxy's own address (i.e. {@code 127.0.0.1:<localPort>}
 * of the {@code kubectl port-forward} in front of the cluster's SOCKS pod),
 * not the ultimate destination. That local port is exactly what {@link
 * ClusterSocksTunnel#getLocalPort()} returns, and — because each context
 * gets its own local port — is enough to disambiguate credentials per
 * cluster.
 *
 * <p>This class is therefore a tiny registry, keyed by local port, that
 * {@link SocksServiceTunnel} instances populate (via {@link
 * #register(ClusterSocksTunnel)}) before making any connection through their
 * owning cluster tunnel. A single instance of this authenticator is
 * installed once, lazily, the first time any {@code SocksServiceTunnel} is
 * constructed (see {@link #installOnce()}), rather than per-connection —
 * repeated {@code setDefault} calls are unnecessary and would race with
 * concurrent connections.
 *
 * <h2>Caveat</h2>
 * Because {@link Authenticator#setDefault(Authenticator)} is process-wide
 * global state, any other code in this JVM that also relies on {@code
 * Authenticator} (e.g. for HTTP proxy auth) would be affected. Tunnellen
 * doesn't use {@code Authenticator} for anything else, so this is safe here,
 * but it's worth calling out explicitly since it's a global side effect
 * rather than something scoped to individual objects.
 */
final class SocksCredentialAuthenticator extends Authenticator {
    private static final Map<Integer, ClusterSocksTunnel> REGISTRY = new ConcurrentHashMap<>();
    private static volatile boolean installed = false;

    private SocksCredentialAuthenticator() {
    }

    /**
     * Installs the shared authenticator as the JVM default, if not already
     * installed. Safe to call multiple times/concurrently.
     */
    static void installOnce() {
        if (!installed) {
            synchronized (SocksCredentialAuthenticator.class) {
                if (!installed) {
                    Authenticator.setDefault(new SocksCredentialAuthenticator());
                    installed = true;
                }
            }
        }
    }

    /** Registers (or updates) the credentials to answer for a given cluster tunnel's local port. */
    static void register(ClusterSocksTunnel clusterTunnel) {
        REGISTRY.put(clusterTunnel.getLocalPort(), clusterTunnel);
    }

    /** Removes a previously registered cluster tunnel's credentials. */
    static void unregister(ClusterSocksTunnel clusterTunnel) {
        REGISTRY.remove(clusterTunnel.getLocalPort(), clusterTunnel);
    }

    @Override
    protected PasswordAuthentication getPasswordAuthentication() {
        if (!"SOCKS5".equals(getRequestingProtocol())) {
            return null;
        }
        var clusterTunnel = REGISTRY.get(getRequestingPort());
        if (clusterTunnel == null) {
            return null;
        }
        return new PasswordAuthentication(clusterTunnel.getUsername(), clusterTunnel.getPassword().toCharArray());
    }
}
