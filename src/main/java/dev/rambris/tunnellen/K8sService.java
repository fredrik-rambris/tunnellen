package dev.rambris.tunnellen;

import java.util.List;

/**
 * A Kubernetes {@code Service}, trimmed down to the fields tunnellen cares about.
 *
 * @param name      the Service's {@code metadata.name}
 * @param namespace the Service's {@code metadata.namespace}
 * @param ports     the Service's {@code spec.ports[]}
 */
public record K8sService(String name, String namespace, List<K8sServicePort> ports) {
}
