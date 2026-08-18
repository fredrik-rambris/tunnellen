package dev.rambris.tunnellen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * BIG-REFACTOR.md step 2.4: converts {@code mode: PORT_FORWARD} tunnels in one or
 * more contexts to {@code mode: SERVICE} (riding a shared cluster SOCKS tunnel),
 * creating the {@code mode: SOCKS} cluster-tunnel entry for a context if it
 * doesn't already have one. Selectable by context and, within that, by service
 * name (substring match against the bare target name, same convention as
 * {@link Web#lookupService}) so a single service can be tried before committing
 * an entire environment.
 *
 * <p>Pure planning/building logic only -- no I/O, no mutation of the {@link
 * Configuration} passed in. Callers ({@code Main}'s CLI entry point, and {@code
 * Web}'s webUI action) are responsible for actually applying the result (adding/
 * removing tunnels, persisting, starting/stopping live processes as appropriate
 * for their context).
 */
public class MigrationHelper {

    /**
     * @param newClusterTunnels contexts selected for migration that don't yet have
     *                          a {@code mode: SOCKS} entry, paired with the new
     *                          entry to add for each (group inferred from an
     *                          existing tunnel in that context, or {@code
     *                          "default"} if none)
     * @param toConvert         existing {@code mode: PORT_FORWARD} tunnels that
     *                          matched the selection and will become {@code mode:
     *                          SERVICE}, paired with their replacement
     * @param skipped           {@code mode: PORT_FORWARD} tunnels in a selected
     *                          context that did NOT match the service filter (only
     *                          non-empty when a filter was given) -- included so a
     *                          dry-run/confirmation can show what was intentionally
     *                          left alone
     */
    public record MigrationPlan(List<Tunnel> newClusterTunnels, List<Conversion> toConvert, List<Tunnel> skipped) {
        public boolean isEmpty() {
            return newClusterTunnels.isEmpty() && toConvert.isEmpty();
        }
    }

    public record Conversion(Tunnel before, Tunnel after) {
    }

    /**
     * Plans a migration for the given contexts (case-sensitive, exact match against
     * {@link Tunnel#getContext()}), optionally restricted to services whose bare
     * target name contains one of {@code serviceFilters} (case-insensitive
     * substring, empty list means "all eligible tunnels in the selected contexts").
     * Safe/idempotent: a context already fully on {@code SOCKS}/{@code SERVICE}
     * mode, or a service filter matching nothing, simply produces an empty plan
     * rather than an error -- callers should report that plainly ("nothing
     * matched") rather than treating it as a failure.
     */
    public static MigrationPlan plan(Configuration config, Set<String> contexts, List<String> serviceFilters) {
        var existingSocksContexts = config.portForwards().stream()
                .filter(t -> t.getMode() == Tunnel.Mode.SOCKS)
                .map(Tunnel::getContext)
                .collect(Collectors.toSet());

        var newClusterTunnels = new ArrayList<Tunnel>();
        for (var context : contexts) {
            if (existingSocksContexts.contains(context)) {
                continue;
            }
            var group = config.portForwards().stream()
                    .filter(t -> context.equals(t.getContext()))
                    .map(Tunnel::getGroup)
                    .findFirst()
                    .orElse("default");
            newClusterTunnels.add(new Tunnel(group, context, null, null, 0, null, true, null, null,
                    Tunnel.Mode.SOCKS, null, null, null));
        }

        var toConvert = new ArrayList<Conversion>();
        var skipped = new ArrayList<Tunnel>();
        for (var t : config.portForwards()) {
            if (!contexts.contains(t.getContext()) || t.getMode() != Tunnel.Mode.PORT_FORWARD) {
                continue;
            }
            if (matchesFilter(t, serviceFilters)) {
                var after = new Tunnel(t.getGroup(), t.getContext(), t.getTarget(), t.getNamespace(),
                        t.getLocalPort(), t.getDestinationPort(), t.isStartOnStartup(), t.getType().orElse(null),
                        t.getDatabase(), Tunnel.Mode.SERVICE, t.getContext(), null, null);
                toConvert.add(new Conversion(t, after));
            } else {
                skipped.add(t);
            }
        }

        return new MigrationPlan(newClusterTunnels, toConvert, skipped);
    }

    private static boolean matchesFilter(Tunnel t, List<String> serviceFilters) {
        if (serviceFilters.isEmpty()) {
            return true;
        }
        var bare = Web.bareTargetName(t.getTarget()).toLowerCase(Locale.ROOT);
        return serviceFilters.stream()
                .map(f -> f.toLowerCase(Locale.ROOT))
                .anyMatch(bare::contains);
    }

    /** A human-readable summary suitable for a CLI dry-run or webUI confirmation. */
    public static String describe(MigrationPlan plan) {
        if (plan.isEmpty()) {
            return "Nothing matched -- no eligible mode:port-forward tunnels found in the selected context(s)"
                    + (plan.skipped().isEmpty() ? "." : ", and " + plan.skipped().size()
                    + " tunnel(s) were skipped because they didn't match the service filter.");
        }
        var sb = new StringBuilder();
        if (!plan.newClusterTunnels().isEmpty()) {
            sb.append("New cluster SOCKS tunnels to create:\n");
            for (var t : plan.newClusterTunnels()) {
                sb.append("  + ").append(t.getContext()).append(" (group ").append(t.getGroup()).append(")\n");
            }
        }
        if (!plan.toConvert().isEmpty()) {
            sb.append("Tunnels to convert to SOCKS:\n");
            for (var c : plan.toConvert()) {
                sb.append("  ~ ").append(c.before().getContext()).append(" ").append(c.before().getTarget())
                        .append(" (port ").append(c.before().getLocalPort()).append(")\n");
            }
        }
        if (!plan.skipped().isEmpty()) {
            sb.append("Skipped (didn't match the service filter):\n");
            for (var t : plan.skipped()) {
                sb.append("  - ").append(t.getContext()).append(" ").append(t.getTarget()).append("\n");
            }
        }
        return sb.toString();
    }
}
