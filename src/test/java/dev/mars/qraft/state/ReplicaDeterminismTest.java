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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.state.catalog.ServiceKey;
import dev.mars.qraft.common.Node;
import dev.mars.qraft.common.NodeStatus;
import dev.mars.qraft.state.catalog.HealthObservation;
import dev.mars.qraft.state.catalog.ServiceCheckId;
import dev.mars.qraft.state.catalog.ServiceHealth;
import dev.mars.qraft.state.catalog.ServiceInstance;
import dev.mars.qraft.raft.RaftCommand;
import dev.mars.qraft.raft.grpc.NodeCommandProto;
import dev.mars.qraft.raft.grpc.NodeCommandType;
import dev.mars.qraft.state.distributed.DistributedStateCommand;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests that replicated state depends only on the committed log: a command decoded from the log, or
 * re-encoded for a lagging follower, reproduces the state of the original; decoding does not read the
 * clock; snapshots are byte-for-byte reproducible; readers and command objects cannot alias replicated
 * state; and a registration cannot claim server-owned lifecycle state or times.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
class ReplicaDeterminismTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Instant NANOS = Instant.parse("2026-09-26T12:00:00.123456789Z");

    private final ProtobufRaftCommandCodec codec = new ProtobufRaftCommandCodec();

    @Test
    void anEntryReEncodedForALaggingFollowerDecodesToTheSameState() throws Exception {
        QraftStateStore decoded = new QraftStateStore();
        QraftStateStore reEncoded = new QraftStateStore();

        for (RaftCommand command : edgeCaseLog()) {
            RaftCommand fromLog = codec.deserialize(codec.serialize(command));
            decoded.apply(fromLog);
            reEncoded.apply(codec.deserialize(codec.serialize(fromLog)));
        }

        assertEquals(snapshotText(decoded), snapshotText(reEncoded));
    }

    @Test
    void registrationDataSurvivesTheLogWithoutCorruption() throws Exception {
        Node original = nodeFromRequest();
        Node bare = Node.of("client-2", "", null, null, null);

        Node decoded = ((NodeCommand.Register) codec.deserialize(
                codec.serialize(NodeCommand.register(original, NANOS)))).node();
        Node decodedBare = ((NodeCommand.Register) codec.deserialize(
                codec.serialize(NodeCommand.register(bare, NANOS)))).node();

        assertEquals(original, decoded);
        assertEquals("", decoded.metadata().get("empty"), "an empty string stays empty");
        assertNull(decoded.region(), "an absent string stays absent");
        assertEquals(bare, decodedBare);
        assertEquals("", decodedBare.address());
        assertNull(decodedBare.datacenter());
        assertNull(decodedBare.registrationTime(), "decoding must not invent a time from the local clock");
    }

    @Test
    void decodingAnEntryDoesNotDependOnWhenItIsDecoded() throws Exception {
        byte[] withoutTimestamp = dev.mars.qraft.raft.grpc.RaftCommandMessage.newBuilder()
                .setNodeCommand(NodeCommandProto.newBuilder().setType(NodeCommandType.NODE_CMD_DEREGISTER)
                        .setName("client-1"))
                .build().toByteArray();

        NodeCommand first = (NodeCommand) codec.deserialize(withoutTimestamp);
        Thread.sleep(5);
        NodeCommand second = (NodeCommand) codec.deserialize(withoutTimestamp);

        assertEquals(first, second, "replaying an entry must not read the replaying node's clock");
        assertEquals(Instant.EPOCH, first.timestamp());
    }

    @Test
    void snapshotsAreByteForByteReproducibleAndRoundTripExactly() throws Exception {
        QraftStateStore store = new QraftStateStore();
        List<RaftCommand> log = new ArrayList<>(edgeCaseLog());
        // Twenty nodes and metadata keys in reverse order: the store holds them in maps whose iteration
        // order is randomised per JVM, so only an explicitly ordered writer produces sorted keys.
        for (char suffix = 't'; suffix >= 'a'; suffix--) {
            log.add(NodeCommand.register(Node.of("order-" + suffix, "10.0.0.2", null, null, null), NANOS));
            log.add(new DistributedStateRaftCommand(DistributedStateCommand.put("order-" + suffix, "v")));
        }
        log.forEach(command -> store.apply(codec.deserialize(codec.serialize(command))));

        byte[] snapshot = store.takeSnapshot();
        QraftStateStore restored = new QraftStateStore();
        restored.restoreSnapshot(snapshot);

        assertEquals(new String(snapshot, StandardCharsets.UTF_8), snapshotText(restored));
        for (String map : List.of("nodes", "metadata")) {
            JsonNode entries = JSON.readTree(snapshot).get(map);
            List<String> order = new ArrayList<>();
            entries.fieldNames().forEachRemaining(order::add);
            assertEquals(true, order.size() >= 20, map + " holds the ordering fixture");
            assertEquals(order.stream().sorted().toList(), order, map + " entries are written in key order");
        }
    }

    @Test
    void readersCannotChangeReplicatedState() throws Exception {
        QraftStateStore store = new QraftStateStore();
        store.apply(NodeCommand.register(nodeFromRequest(), NANOS));
        String before = snapshotText(store);

        Node found = store.findNode("client-1").orElseThrow();
        assertThrows(UnsupportedOperationException.class, () -> found.metadata().put("injected", "yes"));
        assertThrows(UnsupportedOperationException.class, () -> store.getNodes().put("injected", found));
        assertThrows(UnsupportedOperationException.class, () -> store.getNodes().remove("client-1"));
        // A changed node is another value: the one the store holds stays as it was.
        assertEquals(NodeStatus.UNREACHABLE, found.withStatus(NodeStatus.UNREACHABLE).status());
        assertEquals("yes", found.withMetadata("injected", "yes").metadata().get("injected"));

        assertEquals(before, snapshotText(store));
        assertEquals(found, store.findNode("client-1").orElseThrow());
    }

    @Test
    void theCatalogIsExposedOnlyThroughAReadOnlyView() throws Exception {
        QraftStateStore store = new QraftStateStore();
        store.apply(CatalogCommand.register(new ServiceInstance("web", "web", "client-1", "127.0.0.1", 8080,
                List.of(), Map.of(), ServiceHealth.UNKNOWN)));
        Object view = store.getServiceCatalog();

        assertEquals(false, view instanceof dev.mars.qraft.state.catalog.ServiceCatalog,
                "callers cannot reach the mutable catalog, even by casting");
        Set<String> mutators = Set.of("register", "deregister", "deregisterLegacy", "setHealth", "replaceAll", "clear");
        for (java.lang.reflect.Method method : view.getClass().getMethods()) {
            assertEquals(false, mutators.contains(method.getName()), "the view exposes " + method.getName());
        }
        assertEquals(1, store.getServiceCatalog().instances(ServiceKey.inDefaultScope("web")).size());
    }

    @Test
    void commandObjectsAreNotAliasedIntoReplicatedState() throws Exception {
        QraftStateStore store = new QraftStateStore();
        Map<String, String> metadata = new HashMap<>(Map.of("rack", "r7"));
        store.apply(NodeCommand.register(Node.of("client-1", "10.0.0.1", "dc-1", null, metadata), NANOS));
        String before = snapshotText(store);

        metadata.put("mutated", "yes");

        assertEquals(before, snapshotText(store), "a retained command object must not share state with the store");
    }

    @Test
    void registrationCannotClaimServerOwnedLifecycleStateOrTimes() throws Exception {
        QraftStateStore store = new QraftStateStore();

        store.apply(NodeCommand.register(nodeFromRequest(), NANOS));

        Node stored = store.findNode("client-1").orElseThrow();
        assertEquals(NodeStatus.REGISTERING, stored.status(), "the client claimed UNREACHABLE");
        assertEquals(Instant.parse("2026-09-26T12:00:00.123Z"), stored.registrationTime());
        assertNull(stored.lastHeartbeat(), "the client claimed a heartbeat time");
    }

    @Test
    void aBlankRegistrationIdMeansNoRegistrationCheckOnEveryReplica() {
        NodeCommand.Heartbeat heartbeat = (NodeCommand.Heartbeat) NodeCommand.heartbeat(
                "client-1", null, NANOS, 1, " ");

        assertNull(heartbeat.registrationId());
    }

    /** Commands with the edge values that reach the log from real requests. */
    private List<RaftCommand> edgeCaseLog() throws Exception {
        List<RaftCommand> log = new ArrayList<>();
        log.add(NodeCommand.register(nodeFromRequest(), NANOS));
        log.add(NodeCommand.heartbeat("client-1", null, NANOS.plusSeconds(1), 1, ""));
        log.add(NodeCommand.heartbeat("client-1", NodeStatus.UNREACHABLE, NANOS.plusSeconds(2), 2, null));
        log.add(NodeCommand.register(Node.of("client-2", null, null, null, null), NANOS));
        ServiceInstance web = new ServiceInstance("web", "web", "client-1", "127.0.0.1", 8080,
                List.of("b", "a"), Map.of("zone", "a", "tier", ""), ServiceHealth.UNKNOWN,
                "tenant-a", "default", "", "eu-west", false);
        log.add(CatalogCommand.register(web, List.of("ttl", "http")));
        log.add(CatalogCommand.observe(new HealthObservation(new ServiceCheckId(web.identity(), "ttl"),
                ServiceHealth.WARNING, 3, NANOS, 30_000, false, "", 60_000), NANOS));
        log.add(CatalogCommand.expire(new ServiceCheckId(web.identity(), "ttl"), 3,
                Instant.parse("2026-09-26T12:00:30.123Z"), false));
        log.add(NodeCommand.expire("client-2", NANOS, false, NANOS));
        log.add(new DistributedStateRaftCommand(DistributedStateCommand.put("key", "")));
        log.add(new DistributedStateRaftCommand(DistributedStateCommand.put("other", "value")));
        log.add(new DistributedStateRaftCommand(DistributedStateCommand.delete("other")));
        return log;
    }

    /** A node as the HTTP API reads it from a registration, claiming a status and times it does not own. */
    private static Node nodeFromRequest() throws Exception {
        return JSON.readValue("""
                {"name":"client-1","address":"10.0.0.1","region":null,"datacenter":"dc-1",
                 "status":"unreachable","registrationTime":"2000-01-01T00:00:00Z",
                 "lastHeartbeat":"2000-01-01T00:00:01Z",
                 "metadata":{"qraft.registrationId":"reg-1","qraft.version":"1.0.0","empty":""}}
                """, Node.class);
    }

    private static String snapshotText(QraftStateStore store) {
        return new String(store.takeSnapshot(), StandardCharsets.UTF_8);
    }
}
