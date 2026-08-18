package dev.rambris.tunnellen;

/**
 * Pure helper functions for allocating the next free {@code SSSEE} service-index
 * block of local ports, per the port numbering convention documented in
 * {@code BIG-REFACTOR.md} ("Port numbering convention already in use"):
 * {@code localPort = SSSEE}, a 3-digit service index {@code SSS} (starting at
 * {@code 100}) concatenated with a 2-digit environment suffix {@code EE}
 * ({@code 00}=prod, {@code 01}=test, {@code 02}=dev).
 */
public final class PortAllocator {

    /**
     * Local ports below this threshold are not part of the {@code SSSEE} scheme
     * (the scheme's lowest possible value is {@code 100 * 100 = 10000}) and are
     * ignored when computing the next free service index. Existing fixtures
     * (e.g. {@code forwards.yaml}, {@code new-forwards.yaml}) contain legacy or
     * unrelated entries such as {@code 1080}, {@code 5432}, {@code 8001},
     * {@code 8085}, {@code 9000} (minikube/misc contexts) that must not
     * influence the allocation.
     */
    static final int MIN_SCHEME_PORT = 10000;

    private PortAllocator() {
    }

    /**
     * Scans every {@link Tunnel#getLocalPort()} across all groups in {@code config},
     * ignoring ports below {@link #MIN_SCHEME_PORT} (i.e. not part of the {@code SSSEE}
     * scheme), and computes {@code max(SSS) + 1}, where {@code SSS = localPort / 100}
     * (integer division, which discards the {@code EE} environment suffix).
     * <p>
     * This is "next free 3-digit block", not "fill the first gap" — a config with
     * indices {@code {100, 105}} in use returns {@code 106}, not {@code 101}.
     *
     * @return the next free service index, or {@code 100} if {@code config} has no
     * port forwards in the {@code SSSEE} scheme (including an entirely empty config).
     */
    public static int nextServiceIndex(Configuration config) {
        return config.portForwards().stream()
                .mapToInt(Tunnel::getLocalPort)
                .filter(localPort -> localPort >= MIN_SCHEME_PORT)
                .map(localPort -> localPort / 100)
                .max()
                .orElse(99) + 1;
    }

    /**
     * Given a service index (as returned by {@link #nextServiceIndex(Configuration)}),
     * returns the prod/test/dev triad of local ports: {@code serviceIndex*100 + {0,1,2}}.
     */
    public static ServicePorts portsFor(int serviceIndex) {
        var base = serviceIndex * 100;
        return new ServicePorts(base, base + 1, base + 2);
    }

    /**
     * The {@code {prod, test, dev}} local-port triad for one service index, per the
     * {@code EE} suffix convention ({@code 00}=prod, {@code 01}=test, {@code 02}=dev).
     */
    public record ServicePorts(int prod, int test, int dev) {
    }
}
