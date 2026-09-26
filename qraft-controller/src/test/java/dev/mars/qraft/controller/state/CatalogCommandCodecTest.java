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

package dev.mars.qraft.controller.state;

import dev.mars.qraft.catalog.ServiceHealth;
import dev.mars.qraft.catalog.HealthObservation;
import dev.mars.qraft.catalog.ServiceCheckId;
import dev.mars.qraft.catalog.ServiceInstance;
import dev.mars.qraft.distributedstate.DistributedStateCommand;
import dev.mars.qraft.distributedstate.DistributedStateCommandCodec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests catalog command encoding round trips, legacy JSON entry decoding, and rejection of corrupt
 * or incomplete payloads.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-12
 * @version 1.0
 */
class CatalogCommandCodecTest {
    // Fixed protocol timestamps make the serialized values deterministic. The deadline is
    // 45 seconds after acceptance, matching the observation's configured TTL.
    private static final Instant OBSERVED_AT = Instant.parse("2026-09-25T09:59:59Z");
    private static final Instant ACCEPTED_AT = Instant.parse("2026-09-25T10:00:00Z");
    private static final Instant EXPIRY_DEADLINE = Instant.parse("2026-09-25T10:00:45Z");

    private final ProtobufRaftCommandCodec codec = new ProtobufRaftCommandCodec();

    @Test
    void roundTripsRegistrationAndDeregistration() {
        ServiceInstance instance = new ServiceInstance("search-1", "search", "node-1", "10.0.0.4",
                9090, List.of("primary"), Map.of("zone", "a"), ServiceHealth.WARNING,
                "tenant-a", "production", "dc-1", "eu-west", false);

        assertEquals(CatalogCommand.register(instance), codec.deserialize(codec.serialize(CatalogCommand.register(instance))));
        var identity = instance.identity();
        assertEquals(CatalogCommand.deregister(identity),
                codec.deserialize(codec.serialize(CatalogCommand.deregister(identity))));
    }

    @Test
    void roundTripsHealthObservationAndExpiryWithoutRenumberingCatalogCommands() {
        var checkId = new ServiceCheckId(
                new dev.mars.qraft.catalog.ServiceInstanceId(
                        "tenant-a", "production", "node-1", "search-1"), "http");
        var observation = new HealthObservation(checkId, ServiceHealth.WARNING, 9,
                OBSERVED_AT, 45_000, true, "slow response");
        var observe = CatalogCommand.observe(observation, ACCEPTED_AT);
        var expire = CatalogCommand.expire(checkId, 9, EXPIRY_DEADLINE, true);

        assertEquals(observe, codec.deserialize(codec.serialize(observe)));
        assertEquals(expire, codec.deserialize(codec.serialize(expire)));

        // Protobuf enum numbers are part of the persisted Raft wire format. Renumbering
        // these command types would make previously written log entries decode incorrectly.
        assertEquals(3, dev.mars.qraft.controller.raft.grpc.CatalogCommandType.CATALOG_CMD_OBSERVE_HEALTH_VALUE);
        assertEquals(4, dev.mars.qraft.controller.raft.grpc.CatalogCommandType.CATALOG_CMD_EXPIRE_HEALTH_VALUE);
    }

    @Test
    void roundTripsTheDeregistrationDelayAndDecodesOlderObservationsAsNever() throws Exception {
        var checkId = new ServiceCheckId(
                new dev.mars.qraft.catalog.ServiceInstanceId(
                        "tenant-a", "production", "node-1", "search-1"), "ttl");
        var observe = CatalogCommand.observe(new HealthObservation(checkId, ServiceHealth.PASSING, 3,
                OBSERVED_AT, 45_000, true, "", 120_000), ACCEPTED_AT);

        assertEquals(observe, codec.deserialize(codec.serialize(observe)));

        // An entry written before the field existed carries no deregistration delay.
        var olderEntry = dev.mars.qraft.controller.raft.grpc.RaftCommandMessage.parseFrom(codec.serialize(observe))
                .toBuilder();
        olderEntry.getCatalogCommandBuilder().getHealthObservationBuilder().clearDeregisterAfterMs();
        var decoded = (CatalogCommand.ObserveHealth) codec.deserialize(olderEntry.build().toByteArray());
        assertEquals(0, decoded.observation().deregisterAfterMillis());
    }

    @Test
    void distinguishesDeclaredEmptyCheckListsFromUndeclaredRegistrations() {
        ServiceInstance instance = new ServiceInstance("search-1", "search", "node-1", "10.0.0.4",
                9090, List.of(), Map.of(), ServiceHealth.UNKNOWN,
                "tenant-a", "production", "dc-1", "eu-west", true);

        var declared = CatalogCommand.register(instance, List.of("tcp", "http", "tcp"));
        var declaredNone = CatalogCommand.register(instance, List.of());
        var undeclared = CatalogCommand.register(instance);

        assertEquals(List.of("http", "tcp"), ((CatalogCommand.Register) declared).declaredCheckIds(),
                "declared identifiers are sorted and distinct so replicas encode them identically");
        assertEquals(declared, codec.deserialize(codec.serialize(declared)));
        assertEquals(declaredNone, codec.deserialize(codec.serialize(declaredNone)));
        assertEquals(undeclared, codec.deserialize(codec.serialize(undeclared)));
        assertEquals(null, ((CatalogCommand.Register) undeclared).declaredCheckIds());
    }

    @Test
    void readsLegacyJsonDistributedStateEntries() {
        byte[] legacy = new DistributedStateCommandCodec().serialize(DistributedStateCommand.put("key", "value"));

        assertEquals(new DistributedStateRaftCommand(DistributedStateCommand.put("key", "value")),
                codec.deserialize(legacy));
    }

    @Test
    void rejectsCorruptAndIncompleteCatalogPayloads() {
        assertThrows(RuntimeException.class, () -> codec.deserialize(new byte[]{99, 111, 114, 114, 117, 112, 116}));

        var incomplete = dev.mars.qraft.controller.raft.grpc.RaftCommandMessage.newBuilder()
                .setCatalogCommand(dev.mars.qraft.controller.raft.grpc.CatalogCommandProto.newBuilder()
                        .setType(dev.mars.qraft.controller.raft.grpc.CatalogCommandType.CATALOG_CMD_REGISTER))
                .build()
                .toByteArray();
        assertThrows(IllegalArgumentException.class, () -> codec.deserialize(incomplete));
    }
}
