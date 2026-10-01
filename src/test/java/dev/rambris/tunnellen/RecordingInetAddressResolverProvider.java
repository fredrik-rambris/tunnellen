package dev.rambris.tunnellen;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * Test-only {@link InetAddressResolverProvider} (registered via {@code
 * META-INF/services} in test resources, so it applies to the whole test JVM)
 * that records every local lookup of an in-cluster {@code *.svc.cluster.local}
 * name and fails it immediately, mimicking how such a lookup behaves on Linux.
 * All other lookups are delegated to the built-in resolver unchanged.
 *
 * <p>Lets {@link SocksServiceTunnelTest} assert that {@link SocksServiceTunnel}
 * never resolves the remote service name locally — on macOS that lookup goes
 * through mDNS ({@code .local}) and can stall for seconds before the
 * connection is even attempted.
 */
public class RecordingInetAddressResolverProvider extends InetAddressResolverProvider {
    static final List<String> CLUSTER_LOOKUPS = new CopyOnWriteArrayList<>();

    @Override
    public InetAddressResolver get(Configuration configuration) {
        var builtin = configuration.builtinResolver();
        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy) throws UnknownHostException {
                if (host.endsWith(".svc.cluster.local")) {
                    CLUSTER_LOOKUPS.add(host);
                    throw new UnknownHostException(host);
                }
                return builtin.lookupByName(host, lookupPolicy);
            }

            @Override
            public String lookupByAddress(byte[] addr) throws UnknownHostException {
                return builtin.lookupByAddress(addr);
            }
        };
    }

    @Override
    public String name() {
        return "tunnellen-test-recording-resolver";
    }
}
