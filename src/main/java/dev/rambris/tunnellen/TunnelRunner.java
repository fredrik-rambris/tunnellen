package dev.rambris.tunnellen;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Small shared interface implemented by both {@link Tunnel} (direct {@code
 * kubectl port-forward} per tunnel) and {@link SocksServiceTunnel} (a service
 * tunnel riding on a shared {@link ClusterSocksTunnel}), so callers such as
 * {@code KeepAlive}/{@code Web} can treat both kinds of tunnel uniformly
 * without branching on {@link Tunnel.Mode} everywhere (BIG-REFACTOR.md 2.1).
 *
 * <p>BIG-REFACTOR.md step 2.3a added {@link #getLastCheck()}, {@link
 * #getDependsOn()}, {@link #getContext()}, and {@link #getTarget()} so
 * {@code KeepAlive}'s health-check loop (2.2) can operate on either kind of
 * tunnel uniformly instead of only {@code Tunnel}.
 */
public interface TunnelRunner {
    /** Starts the tunnel (opens whatever local listener/process it needs). */
    void start();

    /** Stops the tunnel and releases any resources it's holding. */
    void stop();

    /** Whether the underlying process/listener is currently up. */
    boolean isRunning();

    /** Whether the tunnel has ever been started (successfully or not). */
    boolean isStarted();

    /** Active liveness check (e.g. a TCP connect to the local port). */
    boolean isAlive();

    /** Stable identifier for this tunnel, used for lookups/equality. */
    String getId();

    /** Timestamp of the last {@link #isAlive()} check, or {@code LocalDateTime.MIN} if never checked. */
    LocalDateTime getLastCheck();

    /**
     * The context name of the {@link ClusterSocksTunnel} this tunnel rides
     * on, if any (empty for a direct {@code kubectl port-forward} tunnel).
     */
    Optional<String> getDependsOn();

    /** The kubectl context this tunnel operates in (its own, not what it depends on). */
    String getContext();

    /** The kubectl target (e.g. {@code service/foo}) this tunnel forwards to, for logging. */
    String getTarget();
}
