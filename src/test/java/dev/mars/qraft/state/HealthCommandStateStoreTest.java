/*
 * Copyright 2025 Mark Andrew Ray-Smith Cityline Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.mars.qraft.state;

import dev.mars.qraft.raft.RaftCommandResult;
import dev.mars.qraft.state.catalog.ServiceKey;
import dev.mars.qraft.state.catalog.HealthCheckState;
import dev.mars.qraft.state.catalog.HealthObservation;
import dev.mars.qraft.state.catalog.ServiceCheckId;
import dev.mars.qraft.state.catalog.ServiceHealth;
import dev.mars.qraft.state.catalog.ServiceInstance;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link QraftStateStore} health observations, aggregate service health, exact-match expiry,
 * and health check snapshot round trips.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-25
 * @version 1.0
 */
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
                ttl, 2, secondDeadline, false)));
        assertInstanceOf(RaftCommandResult.Success.class, store.apply(CatalogCommand.expire(
                ttl, 2, secondDeadline, true)));
        assertTrue(store.getServiceCatalog().instances().isEmpty());
        assertTrue(store.healthChecks().isEmpty());
    }

    @Test
    void deregistrationRequiresTheCheckToHaveBeenExpiredFirst() {
        QraftStateStore store = registeredStore();
        ServiceCheckId ttl = check("ttl");
        Instant deadline = ACCEPTED_AT.plusMillis(30_000);
        store.apply(CatalogCommand.observe(observation(ttl, ServiceHealth.PASSING, 1, true, ""), ACCEPTED_AT));

        assertInstanceOf(RaftCommandResult.NoOp.class, store.apply(CatalogCommand.expire(ttl, 1, deadline, true)),
                "a live check cannot skip straight to deregistration");
        assertEquals(1, store.getServiceCatalog().instances().size());

        store.apply(CatalogCommand.expire(ttl, 1, deadline, false));
        assertInstanceOf(RaftCommandResult.Success.class, store.apply(CatalogCommand.expire(ttl, 1, deadline, true)));
        assertTrue(store.getServiceCatalog().instances().isEmpty());
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
                {"clients":{},"metadata":{"version":"3.0"},"services":[],"lastAppliedIndex":4}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(restored.healthChecks().isEmpty());
    }

    @Test
    void snapshotsPreserveTheDeregistrationDelayAndOlderSnapshotsRestoreItAsNever() {
        QraftStateStore store = registeredStore();
        ServiceCheckId check = check("ttl");
        store.apply(CatalogCommand.observe(new HealthObservation(check, ServiceHealth.PASSING, 1,
                Instant.parse("2026-09-25T09:59:59Z"), 30_000, true, "", 90_000), ACCEPTED_AT));

        QraftStateStore restored = new QraftStateStore();
        restored.restoreSnapshot(store.takeSnapshot());
        assertEquals(90_000, restored.findHealthCheck(check).orElseThrow().observation().deregisterAfterMillis());

        QraftStateStore withoutDelay = registeredStore();
        withoutDelay.apply(CatalogCommand.observe(
                observation(check, ServiceHealth.PASSING, 1, true, ""), ACCEPTED_AT));
        String current = new String(withoutDelay.takeSnapshot(), java.nio.charset.StandardCharsets.UTF_8);
        String older = current.replace(",\"deregisterAfterMillis\":0", "");
        assertNotEquals(current, older, "the fixture must omit the field as older snapshots did");
        restored.restoreSnapshot(older.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(0, restored.findHealthCheck(check).orElseThrow().observation().deregisterAfterMillis());
    }

    @Test
    void reRegistrationDeclaringChecksPrunesUndeclaredChecksAndRecomputesHealth() {
        QraftStateStore store = registeredStore();
        store.apply(CatalogCommand.observe(observation(check("http"), ServiceHealth.CRITICAL, 1, true, "down"), ACCEPTED_AT));
        store.apply(CatalogCommand.observe(observation(check("tcp"), ServiceHealth.PASSING, 1, true, "up"), ACCEPTED_AT));
        assertEquals(ServiceHealth.CRITICAL, service(store).health());

        store.apply(CatalogCommand.register(instance(), List.of("tcp")));

        assertTrue(store.findHealthCheck(check("http")).isEmpty(), "a check removed from the client is pruned");
        assertTrue(store.findHealthCheck(check("tcp")).isPresent());
        assertEquals(ServiceHealth.PASSING, service(store).health());

        store.apply(CatalogCommand.register(instance(), List.of()));
        assertTrue(store.healthChecks().isEmpty(), "declaring no checks prunes every check");
        assertEquals(ServiceHealth.UNKNOWN, service(store).health());
    }

    @Test
    void registrationThatDeclaresNothingKeepsExistingChecksForOlderClients() {
        QraftStateStore store = registeredStore();
        store.apply(CatalogCommand.observe(observation(check("http"), ServiceHealth.PASSING, 1, true, "ok"), ACCEPTED_AT));

        store.apply(CatalogCommand.register(instance()));

        assertTrue(store.findHealthCheck(check("http")).isPresent());
        assertInstanceOf(RaftCommandResult.Success.class, store.apply(CatalogCommand.observe(
                observation(check("any"), ServiceHealth.PASSING, 1, true, ""), ACCEPTED_AT)));
    }

    @Test
    void aLateObservationCannotResurrectAnUndeclaredCheck() {
        QraftStateStore store = new QraftStateStore();
        store.apply(CatalogCommand.register(instance(), List.of("tcp")));

        assertInstanceOf(RaftCommandResult.NotFound.class, store.apply(CatalogCommand.observe(
                observation(check("http"), ServiceHealth.CRITICAL, 5, true, "late"), ACCEPTED_AT)));
        assertTrue(store.findHealthCheck(check("http")).isEmpty());
        assertInstanceOf(RaftCommandResult.Success.class, store.apply(CatalogCommand.observe(
                observation(check("tcp"), ServiceHealth.PASSING, 1, true, ""), ACCEPTED_AT)));
    }

    @Test
    void pruningIsScopedToTheExactCompositeInstance() {
        QraftStateStore store = registeredStore();
        ServiceInstance otherNode = new ServiceInstance("web", "web", "node-2", "127.0.0.1", 8080, List.of(),
                Map.of(), ServiceHealth.UNKNOWN, "tenant-a", "production", "dc-1", "eu-west", true);
        store.apply(CatalogCommand.register(otherNode));
        ServiceCheckId otherCheck = new ServiceCheckId(otherNode.identity(), "http");
        store.apply(CatalogCommand.observe(observation(otherCheck, ServiceHealth.PASSING, 1, true, ""), ACCEPTED_AT));
        store.apply(CatalogCommand.observe(observation(check("http"), ServiceHealth.PASSING, 1, true, ""), ACCEPTED_AT));

        store.apply(CatalogCommand.register(instance(), List.of()));

        assertTrue(store.findHealthCheck(check("http")).isEmpty());
        assertTrue(store.findHealthCheck(otherCheck).isPresent(), "another node's checks are untouched");
    }

    @Test
    void declaredChecksSurviveSnapshotsAndAreForgottenOnDeregistration() {
        QraftStateStore store = registeredStore();
        store.apply(CatalogCommand.register(instance(), List.of("tcp")));

        QraftStateStore restored = new QraftStateStore();
        restored.restoreSnapshot(store.takeSnapshot());
        assertInstanceOf(RaftCommandResult.NotFound.class, restored.apply(CatalogCommand.observe(
                observation(check("http"), ServiceHealth.PASSING, 1, true, ""), ACCEPTED_AT)));

        restored.apply(CatalogCommand.deregister(serviceIdentity()));
        restored.apply(CatalogCommand.register(instance()));
        assertInstanceOf(RaftCommandResult.Success.class, restored.apply(CatalogCommand.observe(
                observation(check("http"), ServiceHealth.PASSING, 1, true, ""), ACCEPTED_AT)),
                "a later registration that declares nothing accepts any check");

        String current = new String(registeredStore().takeSnapshot(), java.nio.charset.StandardCharsets.UTF_8);
        String older = current.replace(",\"declaredChecks\":[]", "");
        assertNotEquals(current, older, "the fixture must omit the field as older snapshots did");
        QraftStateStore fromOlder = new QraftStateStore();
        fromOlder.restoreSnapshot(older.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertInstanceOf(RaftCommandResult.Success.class, fromOlder.apply(CatalogCommand.observe(
                observation(check("http"), ServiceHealth.PASSING, 1, true, ""), ACCEPTED_AT)));
    }

    private static ServiceInstance instance() {
        return new ServiceInstance("web", "web", "node-1", "127.0.0.1", 8080, List.of(), Map.of(),
                ServiceHealth.UNKNOWN, "tenant-a", "production", "dc-1", "eu-west", true);
    }

    private static QraftStateStore registeredStore() {
        QraftStateStore store = new QraftStateStore();
        store.apply(CatalogCommand.register(new ServiceInstance(
                "web", "web", "node-1", "127.0.0.1", 8080, List.of(), Map.of(),
                ServiceHealth.UNKNOWN, "tenant-a", "production", "dc-1", "eu-west", true)));
        return store;
    }

    private static ServiceInstance service(QraftStateStore store) {
        return store.getServiceCatalog().instances(new ServiceKey("tenant-a", "production", "web")).getFirst();
    }

    private static ServiceCheckId check(String checkId) {
        return new ServiceCheckId(serviceIdentity(), checkId);
    }

    private static dev.mars.qraft.state.catalog.ServiceInstanceId serviceIdentity() {
        return new dev.mars.qraft.state.catalog.ServiceInstanceId(
                "tenant-a", "production", "node-1", "web");
    }

    private static HealthObservation observation(ServiceCheckId checkId, ServiceHealth status,
                                                  long sequence, boolean required, String output) {
        return new HealthObservation(checkId, status, sequence,
                Instant.parse("2026-09-25T09:59:59Z"), 30_000, required, output);
    }
}
