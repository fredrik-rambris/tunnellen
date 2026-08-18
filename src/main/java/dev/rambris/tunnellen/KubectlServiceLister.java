package dev.rambris.tunnellen;

import ch.qos.logback.classic.Logger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Lists Kubernetes {@code Service}s in a given context/namespace by shelling out to
 * {@code kubectl get service -o json} and parsing the result with Jackson.
 */
public class KubectlServiceLister {
    private static final Logger log = (Logger) LoggerFactory.getLogger(KubectlServiceLister.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KubectlServiceLister() {
    }

    /**
     * Runs {@code kubectl --context=<context> get service -n <namespace> -o json} and parses
     * the result into a list of {@link K8sService}.
     *
     * @param context   the kubectl context to query
     * @param namespace the namespace to scope the query to
     * @return the list of Services found, or an empty list if the query failed or no Services
     * exist in the namespace
     */
    public static List<K8sService> listServices(String context, String namespace) {
        var cmd = new String[]{
                "kubectl",
                "--context=" + context,
                "get", "service",
                "-n", namespace,
                "-o", "json"
        };
        try {
            var proc = new ProcessBuilder(cmd).start();
            proc.getOutputStream().close();
            byte[] stdout;
            try (var is = proc.getInputStream()) {
                stdout = is.readAllBytes();
            }
            boolean exited = proc.waitFor(30, TimeUnit.SECONDS);
            if (!exited) {
                log.error("kubectl get service timed out for context {} namespace {}", context, namespace);
                proc.destroy();
                return Collections.emptyList();
            }
            if (proc.exitValue() != 0) {
                log.error("kubectl get service failed for context {} namespace {} with exit code {}", context, namespace, proc.exitValue());
                return Collections.emptyList();
            }
            return parse(new String(stdout, java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.error("Failed to run kubectl get service for context {} namespace {}: {}", context, namespace, e.getMessage());
            return Collections.emptyList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while running kubectl get service for context {} namespace {}", context, namespace);
            return Collections.emptyList();
        }
    }

    /**
     * Parses the JSON output of {@code kubectl get service -o json} (a Kubernetes {@code List}
     * object with an {@code items[]} array of {@code Service} objects) into a list of
     * {@link K8sService}.
     */
    public static List<K8sService> parse(String json) {
        try {
            return parseTree(MAPPER.readTree(json));
        } catch (IOException e) {
            log.error("Failed to parse kubectl get service JSON output: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Parses the JSON output of {@code kubectl get service -o json} from a stream.
     */
    public static List<K8sService> parse(InputStream json) {
        try {
            return parseTree(MAPPER.readTree(json));
        } catch (IOException e) {
            log.error("Failed to parse kubectl get service JSON output: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private static List<K8sService> parseTree(JsonNode root) {
        var items = root.path("items");
        List<K8sService> services = new ArrayList<>();
        if (!items.isArray()) {
            return services;
        }
        for (JsonNode item : items) {
            var metadata = item.path("metadata");
            var name = metadata.path("name").asText(null);
            var namespace = metadata.path("namespace").asText(null);

            List<K8sServicePort> ports = new ArrayList<>();
            var portsNode = item.path("spec").path("ports");
            if (portsNode.isArray()) {
                for (JsonNode portNode : portsNode) {
                    var portName = portNode.hasNonNull("name") ? portNode.get("name").asText() : null;
                    var port = portNode.path("port").asInt();
                    var targetPort = portNode.hasNonNull("targetPort") ? portNode.get("targetPort").asText() : null;
                    var protocol = portNode.hasNonNull("protocol") ? portNode.get("protocol").asText() : null;
                    ports.add(new K8sServicePort(portName, port, targetPort, protocol));
                }
            }
            services.add(new K8sService(name, namespace, ports));
        }
        return services;
    }
}
