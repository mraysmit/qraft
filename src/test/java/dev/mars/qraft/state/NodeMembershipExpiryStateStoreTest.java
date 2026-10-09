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
import dev.mars.qraft.common.ClientInfo;
import dev.mars.qraft.common.ClientStatus;
import dev.mars.qraft.state.catalog.HealthObservation;
import dev.mars.qraft.state.catalog.ServiceCheckId;
import dev.mars.qraft.state.catalog.ServiceHealth;
import dev.mars.qraft.state.catalog.ServiceInstance;
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
        QraftStateStore store = storeWithNode("client-1", REGISTERED);

        assertInstanceOf(RaftCommandResult.NoOp.class,
                store.apply(ClientCommand.expire("client-1", REGISTERED.plusMillis(1), false, REGISTERED)));
        assertEquals(ClientStatus.REGISTERING, status(store, "client-1"));

        assertInstanceOf(RaftCommandResult.Success.class,
                store.apply(ClientCommand.expire("client-1", REGISTERED, false, REGISTERED)));
        assertEquals(ClientStatus.UNREACHABLE, status(store, "client-1"));
        assertInstanceOf(RaftCommandResult.NoOp.class,
                store.apply(ClientCommand.expire("client-1", REGISTERED, false, REGISTERED)),
                "an unreachable node is not marked again");
        assertInstanceOf(RaftCommandResult.NoOp.class,
                store.apply(ClientCommand.expire("missing", REGISTERED, false, REGISTERED)));
    }

    @Test
    void aHeartbeatAfterExpiryRestoresTheNodeAndDefeatsAStaleReap() {
        QraftStateStore store = storeWithNode("client-1", REGISTERED);
        store.apply(ClientCommand.expire("client-1", REGISTERED, false, REGISTERED));

        store.apply(ClientCommand.heartbeat("client-1", null, REGISTERED.plusSeconds(5), 1));

        assertEquals(ClientStatus.HEALTHY, status(store, "client-1"), "a heartbeat revives an unreachable node");
        assertInstanceOf(RaftCommandResult.NoOp.class,
                store.apply(ClientCommand.expire("client-1", REGISTERED, true, REGISTERED)));
        assertTrue(store.findClient("client-1").isPresent());
    }

    @Test
    void reapingRequiresTheUnreachablePhaseAndRemovesEveryServiceTheNodeRegistered() {
        QraftStateStore store = storeWithNode("client-1", REGISTERED);
        store.apply(ClientCommand.register(node("client-2"), REGISTERED));
        ServiceInstance web = service("web", "client-1", "tenant-a");
        ServiceInstance api = service("api", "client-1", "tenant-b");
        ServiceInstance other = service("web", "client-2", "tenant-a");
        List.of(web, api, other).forEach(instance -> store.apply(CatalogCommand.register(instance, List.of("ttl"))));
        ServiceCheckId webCheck = new ServiceCheckId(web.identity(), "ttl");
        store.apply(CatalogCommand.observe(new HealthObservation(webCheck, ServiceHealth.PASSING, 1, REGISTERED,
                30_000, true, ""), REGISTERED));

        assertInstanceOf(RaftCommandResult.NoOp.class,
                store.apply(ClientCommand.expire("client-1", REGISTERED, true, REGISTERED)),
                "a node that was never marked unreachable cannot be reaped");
        store.apply(ClientCommand.expire("client-1", REGISTERED, false, REGISTERED));
        assertInstanceOf(RaftCommandResult.Success.class,
                store.apply(ClientCommand.expire("client-1", REGISTERED, true, REGISTERED)));

        assertTrue(store.findClient("client-1").isEmpty());
        assertTrue(store.findClient("client-2").isPresent());
        assertEquals(List.of(other), store.getServiceCatalog().instances());
        assertTrue(store.findHealthCheck(webCheck).isEmpty(), "the reaped node's checks go with its services");
        assertInstanceOf(RaftCommandResult.NotFound.class, store.apply(CatalogCommand.observe(new HealthObservation(
                webCheck, ServiceHealth.PASSING, 2, REGISTERED, 30_000, true, ""), REGISTERED)),
                "a late observation cannot resurrect a reaped node's check");
    }

    @Test
    void reRegistrationDefeatsAStaleExpiry() {
        QraftStateStore store = storeWithNode("client-1", REGISTERED);

        store.apply(ClientCommand.register(node("client-1"), REGISTERED.plusSeconds(10)));

        assertInstanceOf(RaftCommandResult.NoOp.class,
                store.apply(ClientCommand.expire("client-1", REGISTERED, false, REGISTERED)));
        assertEquals(ClientStatus.REGISTERING, status(store, "client-1"));
    }

    @Test
    void membershipTimesUseTheReplicatedMillisecondPrecision() {
        Instant withNanos = Instant.parse("2026-09-26T12:00:00.123456789Z");

        ClientCommand.Register register = (ClientCommand.Register) ClientCommand.register(node("client-1"), withNanos);
        ClientCommand.Heartbeat heartbeat = (ClientCommand.Heartbeat) ClientCommand.heartbeat("client-1", null, withNanos, 1);
        ClientCommand.Expire expire = (ClientCommand.Expire) ClientCommand.expire("client-1", withNanos, false, withNanos);

        Instant millis = Instant.parse("2026-09-26T12:00:00.123Z");
        assertEquals(millis, register.timestamp(), "the leader and its followers must hold identical times");
        assertEquals(millis, heartbeat.timestamp());
        assertEquals(millis, expire.expectedLastContact());
        assertEquals(millis, expire.timestamp());
    }

    @Test
    void expiryCommandsRoundTripTheCodecWithoutRenumberingClientCommands() {
        ClientCommand mark = ClientCommand.expire("client-1", REGISTERED, false, REGISTERED.plusSeconds(90));
        ClientCommand reap = ClientCommand.expire("client-1", REGISTERED, true, REGISTERED.plusSeconds(180));

        assertEquals(mark, ClientCodec.fromProto(ClientCodec.toProto(mark)));
        assertEquals(reap, ClientCodec.fromProto(ClientCodec.toProto(reap)));
        assertEquals(5, dev.mars.qraft.raft.grpc.ClientCommandType.CLIENT_CMD_HEARTBEAT_VALUE);
        assertEquals(6, dev.mars.qraft.raft.grpc.ClientCommandType.CLIENT_CMD_EXPIRE_VALUE);
    }

    private static QraftStateStore storeWithNode(String clientId, Instant registeredAt) {
        QraftStateStore store = new QraftStateStore();
        store.apply(ClientCommand.register(node(clientId), registeredAt));
        return store;
    }

    private static ClientInfo node(String clientId) {
        ClientInfo info = new ClientInfo(clientId, clientId + "-host", "127.0.0.1", 8080);
        info.setStatus(ClientStatus.REGISTERING);
        return info;
    }

    private static ServiceInstance service(String serviceId, String nodeId, String tenant) {
        return new ServiceInstance(serviceId, serviceId, nodeId, "127.0.0.1", 8080, List.of(), Map.of(),
                ServiceHealth.UNKNOWN, tenant, "default", "", "", true);
    }

    private static ClientStatus status(QraftStateStore store, String clientId) {
        return store.findClient(clientId).orElseThrow().getStatus();
    }
}
