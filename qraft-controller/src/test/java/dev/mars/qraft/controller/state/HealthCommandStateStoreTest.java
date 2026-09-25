package dev.mars.qraft.controller.state;

import dev.mars.qraft.catalog.HealthCheckState;
import dev.mars.qraft.catalog.HealthObservation;
import dev.mars.qraft.catalog.ServiceCheckId;
import dev.mars.qraft.catalog.ServiceHealth;
import dev.mars.qraft.catalog.ServiceInstance;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthCommandStateStoreTest {
    private static final Instant ACCEPTED_AT = Instant.parse("2026-09-25T10:00:00Z");

    @Test
    void newerObservationsUpdateAddressedChecksAndAggregateServiceHealth() {
        QraftStateStore store = registeredStore();
        ServiceCheckId http = check("http");
        ServiceCheckId tcp = check("tcp");

        assertInstanceOf(RaftCommandResult.Success.class, store.apply(CatalogCommand.observe(
                observation(http, ServiceHealth.PASSING, 1, true, "HTTP 200"), ACCEPTED_AT)));
        assertEquals(ServiceHealth.PASSING, service(store).health());
        assertEquals(ACCEPTED_AT.plusMillis(30_000), store.findHealthCheck(http).orElseThrow().deadline());

        store.apply(CatalogCommand.observe(
                observation(tcp, ServiceHealth.WARNING, 1, true, "slow"), ACCEPTED_AT));
        assertEquals(ServiceHealth.WARNING, service(store).health());

        store.apply(CatalogCommand.observe(
                observation(tcp, ServiceHealth.CRITICAL, 2, true, "refused"), ACCEPTED_AT.plusSeconds(1)));
        assertEquals(ServiceHealth.CRITICAL, service(store).health());
        assertEquals("refused", store.findHealthCheck(tcp).orElseThrow().observation().output());

        assertInstanceOf(RaftCommandResult.Success.class, store.apply(CatalogCommand.observe(
                observation(tcp, ServiceHealth.PASSING, 2, true, "stale equal sequence"),
                ACCEPTED_AT.plusSeconds(2))));
        assertInstanceOf(RaftCommandResult.Success.class, store.apply(CatalogCommand.observe(
                observation(tcp, ServiceHealth.PASSING, 1, true, "stale older sequence"),
                ACCEPTED_AT.plusSeconds(3))));
        assertEquals(ServiceHealth.CRITICAL, service(store).health());
        assertEquals("refused", store.findHealthCheck(tcp).orElseThrow().observation().output());
    }

    @Test
    void optionalFailureDegradesButDoesNotFailAService() {
        QraftStateStore store = registeredStore();
        store.apply(CatalogCommand.observe(
                observation(check("required"), ServiceHealth.PASSING, 1, true, "ok"), ACCEPTED_AT));
        store.apply(CatalogCommand.observe(
                observation(check("optional"), ServiceHealth.CRITICAL, 1, false, "down"), ACCEPTED_AT));

        assertEquals(ServiceHealth.WARNING, service(store).health());
    }

    @Test
    void maintenanceTakesDeterministicPrecedenceAcrossCheckOrder() {
        QraftStateStore first = registeredStore();
        first.apply(CatalogCommand.observe(
                observation(check("critical"), ServiceHealth.CRITICAL, 1, true, "down"), ACCEPTED_AT));
        first.apply(CatalogCommand.observe(
                observation(check("maintenance"), ServiceHealth.MAINTENANCE, 1, true, "planned"), ACCEPTED_AT));

        QraftStateStore second = registeredStore();
        second.apply(CatalogCommand.observe(
                observation(check("maintenance"), ServiceHealth.MAINTENANCE, 1, true, "planned"), ACCEPTED_AT));
        second.apply(CatalogCommand.observe(
                observation(check("critical"), ServiceHealth.CRITICAL, 1, true, "down"), ACCEPTED_AT));

        assertEquals(ServiceHealth.MAINTENANCE, service(first).health());
        assertEquals(service(first).health(), service(second).health());
    }

    @Test
    void expiryMatchesExactSequenceAndDeadlineAndCannotDeleteANewerRenewal() {
        QraftStateStore store = registeredStore();
        ServiceCheckId ttl = check("ttl");
        Instant firstDeadline = ACCEPTED_AT.plusMillis(30_000);
        store.apply(CatalogCommand.observe(
                observation(ttl, ServiceHealth.PASSING, 1, true, "renewed"), ACCEPTED_AT));

        assertInstanceOf(RaftCommandResult.NoOp.class, store.apply(CatalogCommand.expire(
                ttl, 1, firstDeadline.plusMillis(1), false)));
        assertFalse(store.findHealthCheck(ttl).orElseThrow().expired());

        assertInstanceOf(RaftCommandResult.Success.class, store.apply(CatalogCommand.expire(
                ttl, 1, firstDeadline, false)));
        assertTrue(store.findHealthCheck(ttl).orElseThrow().expired());
        assertEquals(ServiceHealth.CRITICAL, service(store).health());

        Instant secondAcceptedAt = ACCEPTED_AT.plusSeconds(10);
        store.apply(CatalogCommand.observe(
                observation(ttl, ServiceHealth.PASSING, 2, true, "renewed again"), secondAcceptedAt));
        assertFalse(store.findHealthCheck(ttl).orElseThrow().expired());
        assertEquals(ServiceHealth.PASSING, service(store).health());
        assertInstanceOf(RaftCommandResult.NoOp.class, store.apply(CatalogCommand.expire(
                ttl, 1, firstDeadline, true)));
        assertFalse(store.getServiceCatalog().instances().isEmpty());

        Instant secondDeadline = secondAcceptedAt.plusMillis(30_000);
        assertInstanceOf(RaftCommandResult.Success.class, store.apply(CatalogCommand.expire(
                ttl, 2, secondDeadline, true)));
        assertTrue(store.getServiceCatalog().instances().isEmpty());
        assertTrue(store.healthChecks().isEmpty());
    }

    @Test
    void healthChecksRoundTripSnapshotsAndLegacySnapshotsDefaultToEmpty() {
        QraftStateStore store = registeredStore();
        ServiceCheckId check = check("http");
        store.apply(CatalogCommand.observe(
                observation(check, ServiceHealth.WARNING, 3, true, "slow"), ACCEPTED_AT));
        byte[] snapshot = store.takeSnapshot();

        QraftStateStore restored = new QraftStateStore();
        restored.restoreSnapshot(snapshot);
        HealthCheckState restoredCheck = restored.findHealthCheck(check).orElseThrow();
        assertEquals(3, restoredCheck.observation().sequenceNumber());
        assertEquals(ServiceHealth.WARNING, service(restored).health());
        assertEquals(new String(snapshot, java.nio.charset.StandardCharsets.UTF_8),
                new String(restored.takeSnapshot(), java.nio.charset.StandardCharsets.UTF_8));

        restored.restoreSnapshot("""
                {"agents":{},"metadata":{"version":"3.0"},"services":[],"lastAppliedIndex":4}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(restored.healthChecks().isEmpty());
    }

    private static QraftStateStore registeredStore() {
        QraftStateStore store = new QraftStateStore();
        store.apply(CatalogCommand.register(new ServiceInstance(
                "web", "web", "node-1", "127.0.0.1", 8080, List.of(), Map.of(),
                ServiceHealth.UNKNOWN, "tenant-a", "production", "dc-1", "eu-west", true)));
        return store;
    }

    private static ServiceInstance service(QraftStateStore store) {
        return store.getServiceCatalog().instances("web").getFirst();
    }

    private static ServiceCheckId check(String checkId) {
        return new ServiceCheckId(serviceIdentity(), checkId);
    }

    private static dev.mars.qraft.catalog.ServiceInstanceId serviceIdentity() {
        return new dev.mars.qraft.catalog.ServiceInstanceId(
                "tenant-a", "production", "node-1", "web");
    }

    private static HealthObservation observation(ServiceCheckId checkId, ServiceHealth status,
                                                  long sequence, boolean required, String output) {
        return new HealthObservation(checkId, status, sequence,
                Instant.parse("2026-09-25T09:59:59Z"), 30_000, required, output);
    }
}
