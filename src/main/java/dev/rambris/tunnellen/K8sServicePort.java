package dev.rambris.tunnellen;

/**
 * A single port entry from a Kubernetes {@code Service}'s {@code spec.ports[]}.
 *
 * @param name       port name, may be {@code null} for a Service with a single unnamed port
 * @param port       the Service's exposed port
 * @param targetPort the target port on the backing Pod(s); Kubernetes allows this to be either
 *                   a numeric port or a named container port, so it is kept as a {@code String}
 * @param protocol   e.g. {@code "TCP"}, {@code "UDP"}
 */
public record K8sServicePort(String name, int port, String targetPort, String protocol) {
}
