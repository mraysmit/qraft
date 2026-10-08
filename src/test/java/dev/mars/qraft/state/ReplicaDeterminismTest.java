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
import dev.mars.qraft.common.AgentCapabilities;
import dev.mars.qraft.common.AgentInfo;
import dev.mars.qraft.common.AgentStatus;
import dev.mars.qraft.state.catalog.HealthObservation;
import dev.mars.qraft.state.catalog.ServiceCheckId;
import dev.mars.qraft.state.catalog.ServiceHealth;
import dev.mars.qraft.state.catalog.ServiceInstance;
import dev.mars.qraft.raft.RaftCommand;
import dev.mars.qraft.raft.grpc.AgentCommandProto;
import dev.mars.qraft.raft.grpc.AgentCommandType;
import dev.mars.qraft.state.distributed.DistributedStateCommand;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
        AgentInfo original = agentFromRequest();
        AgentInfo bare = new AgentInfo("agent-2", null, "", 0);
        bare.setRegistrationTime(null);

        AgentInfo decoded = ((AgentCommand.Register) codec.deserialize(
                codec.serialize(AgentCommand.register(original, NANOS)))).agentInfo();
        AgentInfo decodedBare = ((AgentCommand.Register) codec.deserialize(
                codec.serialize(AgentCommand.register(bare, NANOS)))).agentInfo();

        assertEquals(original.getCapabilities().getCustomCapabilities(),
                decoded.getCapabilities().getCustomCapabilities(), "typed capability values keep their types");
        assertEquals("", decoded.getHostname(), "an empty string stays empty");
        assertEquals("", decoded.getVersion());
        assertNull(decoded.getRegion(), "an absent string stays absent");
        assertNull(decoded.getCapabilities().getSystemInfo().getArchitecture());
        assertNull(decoded.getCapabilities().getNetworkInfo().getPublicIpAddress());
        assertEquals(original.getMetadata(), decoded.getMetadata());
        assertNull(decodedBare.getHostname());
        assertEquals("", decodedBare.getAddress());
        assertNull(decodedBare.getRegistrationTime(), "decoding must not invent a time from the local clock");
    }

    @Test
    void decodingAnEntryDoesNotDependOnWhenItIsDecoded() throws Exception {
        byte[] withoutTimestamp = dev.mars.qraft.raft.grpc.RaftCommandMessage.newBuilder()
                .setAgentCommand(AgentCommandProto.newBuilder().setType(AgentCommandType.AGENT_CMD_DEREGISTER)
                        .setAgentId("agent-1"))
                .build().toByteArray();

        AgentCommand first = (AgentCommand) codec.deserialize(withoutTimestamp);
        Thread.sleep(5);
        AgentCommand second = (AgentCommand) codec.deserialize(withoutTimestamp);

        assertEquals(first, second, "replaying an entry must not read the replaying node's clock");
        assertEquals(Instant.EPOCH, first.timestamp());
    }

    @Test
    void snapshotsAreByteForByteReproducibleAndRoundTripExactly() throws Exception {
        QraftStateStore store = new QraftStateStore();
        List<RaftCommand> log = new ArrayList<>(edgeCaseLog());
        // Twenty agents and metadata keys in reverse order: the store holds them in maps whose iteration
        // order is randomised per JVM, so only an explicitly ordered writer produces sorted keys.
        for (char suffix = 't'; suffix >= 'a'; suffix--) {
            log.add(AgentCommand.register(new AgentInfo("order-" + suffix, "host", "10.0.0.2", 8080), NANOS));
            log.add(new DistributedStateRaftCommand(DistributedStateCommand.put("order-" + suffix, "v")));
        }
        log.forEach(command -> store.apply(codec.deserialize(codec.serialize(command))));

        byte[] snapshot = store.takeSnapshot();
        QraftStateStore restored = new QraftStateStore();
        restored.restoreSnapshot(snapshot);

        assertEquals(new String(snapshot, StandardCharsets.UTF_8), snapshotText(restored));
        for (String map : List.of("agents", "metadata")) {
            JsonNode entries = JSON.readTree(snapshot).get(map);
            List<String> order = new ArrayList<>();
            entries.fieldNames().forEachRemaining(order::add);
            assertEquals(true, order.size() >= 20, map + " holds the ordering fixture");
            assertEquals(order.stream().sorted().toList(), order, map + " entries are written in key order");
        }
    }

    @Test
    void readersReceiveCopiesThatCannotChangeReplicatedState() throws Exception {
        QraftStateStore store = new QraftStateStore();
        store.apply(AgentCommand.register(agentFromRequest(), NANOS));
        String before = snapshotText(store);

        AgentInfo found = store.findAgent("agent-1").orElseThrow();
        found.setStatus(AgentStatus.FAILED);
        found.getMetadata().put("injected", "yes");
        found.getCapabilities().getSupportedServices().add("injected");
        AgentInfo listed = store.getAgents().get("agent-1");
        listed.setRegion("injected");

        assertEquals(before, snapshotText(store));
    }

    @Test
    void theCatalogIsExposedOnlyThroughAReadOnlyView() throws Exception {
        QraftStateStore store = new QraftStateStore();
        store.apply(CatalogCommand.register(new ServiceInstance("web", "web", "agent-1", "127.0.0.1", 8080,
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
        AgentInfo registered = agentFromRequest();
        store.apply(AgentCommand.register(registered, NANOS));
        AgentCapabilities capabilities = new AgentCapabilities();
        capabilities.setSupportedServices(new java.util.HashSet<>(Set.of("kv")));
        store.apply(AgentCommand.updateCapabilities("agent-1", capabilities));
        String before = snapshotText(store);

        registered.getCapabilities().getSupportedServices().add("mutated");
        registered.getMetadata().put("mutated", "yes");
        capabilities.getSupportedServices().add("mutated");

        assertEquals(before, snapshotText(store), "a retained command object must not share state with the store");
    }

    @Test
    void registrationCannotClaimServerOwnedLifecycleStateOrTimes() throws Exception {
        QraftStateStore store = new QraftStateStore();

        store.apply(AgentCommand.register(agentFromRequest(), NANOS));

        AgentInfo stored = store.findAgent("agent-1").orElseThrow();
        assertEquals(AgentStatus.REGISTERING, stored.getStatus(), "the agent claimed UNREACHABLE");
        assertEquals(Instant.parse("2026-09-26T12:00:00.123Z"), stored.getRegistrationTime());
        assertNull(stored.getLastHeartbeat(), "the agent claimed a heartbeat time");
    }

    @Test
    void aBlankRegistrationIdMeansNoRegistrationCheckOnEveryReplica() {
        AgentCommand.Heartbeat heartbeat = (AgentCommand.Heartbeat) AgentCommand.heartbeat(
                "agent-1", null, NANOS, 1, " ");

        assertNull(heartbeat.registrationId());
    }

    /** Commands with the edge values that reach the log from real requests. */
    private List<RaftCommand> edgeCaseLog() throws Exception {
        List<RaftCommand> log = new ArrayList<>();
        log.add(AgentCommand.register(agentFromRequest(), NANOS));
        log.add(AgentCommand.heartbeat("agent-1", null, NANOS.plusSeconds(1), 1, ""));
        log.add(AgentCommand.heartbeat("agent-1", AgentStatus.DEGRADED, NANOS.plusSeconds(2), 2, null));
        AgentInfo bare = new AgentInfo("agent-2", null, null, 0);
        bare.setMetadata(null);
        log.add(AgentCommand.register(bare, NANOS));
        ServiceInstance web = new ServiceInstance("web", "web", "agent-1", "127.0.0.1", 8080,
                List.of("b", "a"), Map.of("zone", "a", "tier", ""), ServiceHealth.UNKNOWN,
                "tenant-a", "default", "", "eu-west", false);
        log.add(CatalogCommand.register(web, List.of("ttl", "http")));
        log.add(CatalogCommand.observe(new HealthObservation(new ServiceCheckId(web.identity(), "ttl"),
                ServiceHealth.WARNING, 3, NANOS, 30_000, false, "", 60_000), NANOS));
        log.add(CatalogCommand.expire(new ServiceCheckId(web.identity(), "ttl"), 3,
                Instant.parse("2026-09-26T12:00:30.123Z"), false));
        log.add(AgentCommand.expire("agent-2", NANOS, false, NANOS));
        log.add(new DistributedStateRaftCommand(DistributedStateCommand.put("key", "")));
        log.add(new DistributedStateRaftCommand(DistributedStateCommand.put("other", "value")));
        log.add(new DistributedStateRaftCommand(DistributedStateCommand.delete("other")));
        return log;
    }

    /** An agent registration as the HTTP API deserializes it from an agent's request body. */
    private static AgentInfo agentFromRequest() throws Exception {
        return JSON.readValue("""
                {"agentId":"agent-1","hostname":"","address":"10.0.0.1","port":8080,
                 "status":"unreachable","registrationTime":"2000-01-01T00:00:00Z",
                 "lastHeartbeat":"2000-01-01T00:00:01Z","version":"","region":null,"datacenter":"dc-1",
                 "metadata":{"qraft.registrationId":"reg-1","empty":""},
                 "capabilities":{
                   "supportedServices":["kv","health"],"availableRegions":[],
                   "customCapabilities":{"slots":5,"gpu":true,"tier":"gold","ratio":0.5,
                                         "labels":["a","b"],"nothing":null},
                   "systemInfo":{"operatingSystem":"linux","cpuCores":4,"cpuUsage":12.5},
                   "networkInfo":{"privateIpAddress":"10.0.0.1","firewallPorts":[80,443],
                                  "networkInterfaces":null}}}
                """, AgentInfo.class);
    }

    private static String snapshotText(QraftStateStore store) {
        return new String(store.takeSnapshot(), StandardCharsets.UTF_8);
    }
}
