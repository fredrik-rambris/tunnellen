package dev.rambris.tunnellen;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class KubectlServiceListerTest {

    private static final File FIXTURE = new File(
            KubectlServiceListerTest.class.getResource("/fixtures/sample-services.json").getFile());

    private static String fixtureJson() throws IOException {
        return Files.readString(FIXTURE.toPath());
    }

    @Test
    void parseReturnsExpectedNumberOfServices() throws IOException {
        var services = KubectlServiceLister.parse(fixtureJson());

        assertEquals(4, services.size());
    }

    @Test
    void parseReadsServiceNamesAndNamespaces() throws IOException {
        var services = KubectlServiceLister.parse(fixtureJson());

        assertEquals("akb-frontend-storybook", services.get(0).name());
        assertEquals("default", services.get(0).namespace());
        assertEquals("akbse-frontend-es-master", services.get(1).name());
        assertEquals("akbse-frontend-api", services.get(2).name());
        assertEquals("bhg-sftpgo", services.get(3).name());
    }

    @Test
    void parseReadsUnnamedSinglePortWithNumericTargetPort() throws IOException {
        var services = KubectlServiceLister.parse(fixtureJson());

        var ports = services.get(0).ports();
        assertEquals(1, ports.size());
        var port = ports.get(0);
        assertNull(port.name());
        assertEquals(80, port.port());
        assertEquals("80", port.targetPort());
        assertEquals("TCP", port.protocol());
    }

    @Test
    void parseReadsMultiplePortsWithNumericTargetPorts() throws IOException {
        var services = KubectlServiceLister.parse(fixtureJson());

        var ports = services.get(1).ports();
        assertEquals(2, ports.size());
        assertEquals("http", ports.get(0).name());
        assertEquals(9200, ports.get(0).port());
        assertEquals("9200", ports.get(0).targetPort());
        assertEquals("transport", ports.get(1).name());
        assertEquals(9300, ports.get(1).port());
    }

    @Test
    void parseReadsNamedTargetPort() throws IOException {
        var services = KubectlServiceLister.parse(fixtureJson());

        var ports = services.get(2).ports();
        assertEquals(1, ports.size());
        var port = ports.get(0);
        assertEquals("http", port.name());
        assertEquals(8000, port.port());
        assertEquals("http", port.targetPort());
        assertEquals("TCP", port.protocol());
    }

    @Test
    void parseReadsMultiplePortsWithMixOfNames() throws IOException {
        var services = KubectlServiceLister.parse(fixtureJson());

        var ports = services.get(3).ports();
        assertEquals(3, ports.size());
        assertEquals("sftp", ports.get(0).name());
        assertEquals(22, ports.get(0).port());
        assertEquals("http", ports.get(1).name());
        assertEquals(80, ports.get(1).port());
        assertEquals("telemetry", ports.get(2).name());
        assertEquals(10000, ports.get(2).port());
    }

    @Test
    void parseHandlesEmptyItemsList() {
        var services = KubectlServiceLister.parse("{\"kind\":\"List\",\"items\":[]}");

        assertTrue(services.isEmpty());
    }

    @Test
    void parseHandlesInvalidJsonGracefully() {
        var services = KubectlServiceLister.parse("not json");

        assertTrue(services.isEmpty());
    }
}
