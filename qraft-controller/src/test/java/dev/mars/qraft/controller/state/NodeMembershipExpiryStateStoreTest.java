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

import dev.mars.qraft.agent.AgentInfo;
import dev.mars.qraft.agent.AgentStatus;
import dev.mars.qraft.catalog.HealthObservation;
import dev.mars.qraft.catalog.ServiceCheckId;
import dev.mars.qraft.catalog.ServiceHealth;
import dev.mars.qraft.catalog.ServiceInstance;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests replicated node membership expiry in {@link QraftStateStore}: a silent node is marked
 * unreachable only for its exact last contact, a heartbeat or re-registration defeats a stale command,
 * and reaping requires the unreachable phase and removes the node with every service it registered.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
class NodeMembershipExpiryStateStoreTest {
    private static final Instant REGISTERED = Instant.parse("2026-09-26T12:00:00Z");

    @Test
    void expiryMarksASilentNodeUnreachableOnlyForItsExactLastContact() {
        QraftStateStore store = storeWithNode("agent-1", REGISTERED);

        assertInstanceOf(RaftCommandResult.NoOp.class,
                store.apply(AgentCommand.expire("agent-1", REGISTERED.plusMillis(1), false, REGISTERED)));
        assertEquals(AgentStatus.REGISTERING, status(store, "agent-1"));

        assertInstanceOf(RaftCommandResult.Success.class,
                store.apply(AgentCommand.expire("agent-1", REGISTERED, false, REGISTERED)));
        assertEquals(AgentStatus.UNREACHABLE, status(store, "agent-1"));
        assertInstanceOf(RaftCommandResult.NoOp.class,
                store.apply(AgentCommand.expire("agent-1", REGISTERED, false, REGISTERED)),
                "an unreachable node is not marked again");
        assertInstanceOf(RaftCommandResult.NoOp.class,
                store.apply(AgentCommand.expire("missing", REGISTERED, false, REGISTERED)));
    }

    @Test
    void aHeartbeatAfterExpiryRestoresTheNodeAndDefeatsAStaleReap() {
        QraftStateStore store = storeWithNode("agent-1", REGISTERED);
        store.apply(AgentCommand.expire("agent-1", REGISTERED, false, REGISTERED));

        store.apply(AgentCommand.heartbeat("agent-1", null, REGISTERED.plusSeconds(5), 1));

        assertEquals(AgentStatus.HEALTHY, status(store, "agent-1"), "a heartbeat revives an unreachable node");
        assertInstanceOf(RaftCommandResult.NoOp.class,
                store.apply(AgentCommand.expire("agent-1", REGISTERED, true, REGISTERED)));
        assertTrue(store.findAgent("agent-1").isPresent());
    }

    @Test
    void reapingRequiresTheUnreachablePhaseAndRemovesEveryServiceTheNodeRegistered() {
        QraftStateStore store = storeWithNode("agent-1", REGISTERED);
        store.apply(AgentCommand.register(node("agent-2"), REGISTERED));
        ServiceInstance web = service("web", "agent-1", "tenant-a");
        ServiceInstance api = service("api", "agent-1", "tenant-b");
        ServiceInstance other = service("web", "agent-2", "tenant-a");
        List.of(web, api, other).forEach(instance -> store.apply(CatalogCommand.register(instance, List.of("ttl"))));
        ServiceCheckId webCheck = new ServiceCheckId(web.identity(), "ttl");
        store.apply(CatalogCommand.observe(new HealthObservation(webCheck, ServiceHealth.PASSING, 1, REGISTERED,
                30_000, true, ""), REGISTERED));

        assertInstanceOf(RaftCommandResult.NoOp.class,
                store.apply(AgentCommand.expire("agent-1", REGISTERED, true, REGISTERED)),
                "a node that was never marked unreachable cannot be reaped");
        store.apply(AgentCommand.expire("agent-1", REGISTERED, false, REGISTERED));
        assertInstanceOf(RaftCommandResult.Success.class,
                store.apply(AgentCommand.expire("agent-1", REGISTERED, true, REGISTERED)));

        assertTrue(store.findAgent("agent-1").isEmpty());
        assertTrue(store.findAgent("agent-2").isPresent());
        assertEquals(List.of(other), store.getServiceCatalog().instances());
        assertTrue(store.findHealthCheck(webCheck).isEmpty(), "the reaped node's checks go with its services");
        assertInstanceOf(RaftCommandResult.NotFound.class, store.apply(CatalogCommand.observe(new HealthObservation(
                webCheck, ServiceHealth.PASSING, 2, REGISTERED, 30_000, true, ""), REGISTERED)),
                "a late observation cannot resurrect a reaped node's check");
    }

    @Test
    void reRegistrationDefeatsAStaleExpiry() {
        QraftStateStore store = storeWithNode("agent-1", REGISTERED);

        store.apply(AgentCommand.register(node("agent-1"), REGISTERED.plusSeconds(10)));

        assertInstanceOf(RaftCommandResult.NoOp.class,
                store.apply(AgentCommand.expire("agent-1", REGISTERED, false, REGISTERED)));
        assertEquals(AgentStatus.REGISTERING, status(store, "agent-1"));
    }

    @Test
    void membershipTimesUseTheReplicatedMillisecondPrecision() {
        Instant withNanos = Instant.parse("2026-09-26T12:00:00.123456789Z");

        AgentCommand.Register register = (AgentCommand.Register) AgentCommand.register(node("agent-1"), withNanos);
        AgentCommand.Heartbeat heartbeat = (AgentCommand.Heartbeat) AgentCommand.heartbeat("agent-1", null, withNanos, 1);
        AgentCommand.Expire expire = (AgentCommand.Expire) AgentCommand.expire("agent-1", withNanos, false, withNanos);

        Instant millis = Instant.parse("2026-09-26T12:00:00.123Z");
        assertEquals(millis, register.timestamp(), "the leader and its followers must hold identical times");
        assertEquals(millis, heartbeat.timestamp());
        assertEquals(millis, expire.expectedLastContact());
        assertEquals(millis, expire.timestamp());
    }

    @Test
    void expiryCommandsRoundTripTheCodecWithoutRenumberingAgentCommands() {
        AgentCommand mark = AgentCommand.expire("agent-1", REGISTERED, false, REGISTERED.plusSeconds(90));
        AgentCommand reap = AgentCommand.expire("agent-1", REGISTERED, true, REGISTERED.plusSeconds(180));

        assertEquals(mark, AgentCodec.fromProto(AgentCodec.toProto(mark)));
        assertEquals(reap, AgentCodec.fromProto(AgentCodec.toProto(reap)));
        assertEquals(5, dev.mars.qraft.controller.raft.grpc.AgentCommandType.AGENT_CMD_HEARTBEAT_VALUE);
        assertEquals(6, dev.mars.qraft.controller.raft.grpc.AgentCommandType.AGENT_CMD_EXPIRE_VALUE);
    }

    private static QraftStateStore storeWithNode(String agentId, Instant registeredAt) {
        QraftStateStore store = new QraftStateStore();
        store.apply(AgentCommand.register(node(agentId), registeredAt));
        return store;
    }

    private static AgentInfo node(String agentId) {
        AgentInfo info = new AgentInfo(agentId, agentId + "-host", "127.0.0.1", 8080);
        info.setStatus(AgentStatus.REGISTERING);
        return info;
    }

    private static ServiceInstance service(String serviceId, String nodeId, String tenant) {
        return new ServiceInstance(serviceId, serviceId, nodeId, "127.0.0.1", 8080, List.of(), Map.of(),
                ServiceHealth.UNKNOWN, tenant, "default", "", "", true);
    }

    private static AgentStatus status(QraftStateStore store, String agentId) {
        return store.findAgent(agentId).orElseThrow().getStatus();
    }
}
