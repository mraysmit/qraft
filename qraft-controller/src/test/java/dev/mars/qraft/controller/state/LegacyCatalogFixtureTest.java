package dev.mars.qraft.controller.state;

import dev.mars.qraft.agent.AgentStatus;
import dev.mars.qraft.catalog.ServiceHealth;
import dev.mars.qraft.catalog.ServiceInstance;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class LegacyCatalogFixtureTest {
    private static final String FIXTURE_ROOT = "/fixtures/catalog/";
    private static final Map<String, String> SHA_256 = Map.of(
            "register-rich.bin", "f4cc4fb7aa5b7e6cc1e3710e0d7dcf8b5e3a47846e24d2d27d9042595545528f",
            "register-empty.bin", "170617b5656cc81f099f7e12cde6c985826c58a8ffc864735e41903f249f52fc",
            "deregister.bin", "31dfb4a90cf9eaeddb0be34ef020f7c6abdfae2479cf76f04647c16562eba3fd",
            "catalog-snapshot.json", "26d75e529a639281f78bae6bce7c82120fd22e3ca3480857e926fc533f747423");
    private final ProtobufRaftCommandCodec codec = new ProtobufRaftCommandCodec();

    @Test
    void decodesLegacyCatalogCommandsWithExactFields() throws Exception {
        ServiceInstance richInstance = new ServiceInstance(
                "web-a", "web", "node-a", "10.0.0.10", 8080,
                List.of("blue", "v1"), Map.of("team", "platform", "zone", "a"),
                ServiceHealth.PASSING);
        ServiceInstance emptyInstance = new ServiceInstance(
                "worker-1", "worker", "node-c", "10.0.0.30", 9090,
                List.of(), Map.of(), ServiceHealth.FAILING);

        assertEquals(CatalogCommand.register(richInstance),
                codec.deserialize(fixture("register-rich.bin")));
        assertEquals(CatalogCommand.register(emptyInstance),
                codec.deserialize(fixture("register-empty.bin")));
        assertEquals(CatalogCommand.deregister("legacy-web"),
                codec.deserialize(fixture("deregister.bin")));
        assertEquals("default", richInstance.tenantId());
        assertEquals("default", richInstance.namespace());
        assertEquals("", richInstance.datacenter());
        assertEquals("", richInstance.region());
        assertEquals(true, richInstance.enabled());
    }

    @Test
    void restoresLegacyCatalogSnapshotWithExactState() throws Exception {
        QraftStateStore store = new QraftStateStore();

        store.restoreSnapshot(fixture("catalog-snapshot.json"));

        assertEquals(42, store.getLastAppliedIndex());
        assertEquals("fixture", store.getMetadata("source"));
        assertEquals(List.of(
                new ServiceInstance("web-a", "web", "node-a", "10.0.0.10", 8080,
                        List.of("blue"), Map.of("zone", "a"), ServiceHealth.PASSING),
                new ServiceInstance("web-b", "web", "node-b", "10.0.0.20", 8081,
                        List.of("green"), Map.of("zone", "b"), ServiceHealth.WARNING)),
                store.getServiceCatalog().instances("web"));

        var agent = store.findAgent("agent-legacy").orElseThrow();
        assertEquals("legacy-host", agent.getHostname());
        assertEquals("192.0.2.10", agent.getAddress());
        assertEquals(8500, agent.getPort());
        assertEquals(AgentStatus.ACTIVE, agent.getStatus());
        assertEquals("2.9.0", agent.getVersion());
        assertEquals("eu-west", agent.getRegion());
        assertEquals("dc-legacy", agent.getDatacenter());
        assertEquals(Map.of("rack", "r1"), agent.getMetadata());

        byte[] migrated = store.takeSnapshot();
        assertArrayEquals(migrated, store.takeSnapshot(),
                "restored legacy state must serialize deterministically");
        QraftStateStore reopened = new QraftStateStore();
        reopened.restoreSnapshot(migrated);
        assertEquals(store.getServiceCatalog().instances(), reopened.getServiceCatalog().instances());
        assertEquals(42, reopened.getLastAppliedIndex());
    }

    @Test
    void fixtureBytesRemainImmutable() throws Exception {
        for (var expected : SHA_256.entrySet()) {
            byte[] bytes = fixture(expected.getKey());
            String actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
            assertEquals(expected.getValue(), actual, expected.getKey());
        }
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream input = LegacyCatalogFixtureTest.class.getResourceAsStream(FIXTURE_ROOT + name)) {
            assertNotNull(input, "missing immutable legacy fixture " + FIXTURE_ROOT + name);
            return input.readAllBytes();
        }
    }
}
