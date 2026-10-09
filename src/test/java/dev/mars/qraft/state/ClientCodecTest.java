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

import dev.mars.qraft.common.ClientCapabilities;
import dev.mars.qraft.common.ClientInfo;
import dev.mars.qraft.common.ClientStatus;
import dev.mars.qraft.raft.grpc.ClientCommandProto;
import dev.mars.qraft.raft.grpc.ClientStatusProto;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests {@link ClientCodec} round trips for every client command variant, rejection of unspecified
 * types, and typed command factories.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-09
 * @version 1.0
 */
class ClientCodecTest {

    @Test
    void roundTripsEveryClientCommandVariant() {
        Instant timestamp = Instant.parse("2026-02-03T04:05:06Z");
        ClientCapabilities capabilities = new ClientCapabilities();
        capabilities.setSupportedServices(Set.of("kv", "health"));
        capabilities.setAvailableRegions(Set.of("eu-west"));

        ClientInfo info = new ClientInfo("client-1", "host", "10.0.0.1", 8080);
        info.setStatus(ClientStatus.HEALTHY);
        info.setCapabilities(capabilities);
        info.setVersion("1.2.3");
        info.setRegion("eu-west");
        info.setDatacenter("dc-1");

        List<ClientCommand> commands = List.of(
                new ClientCommand.Register("client-1", info, timestamp),
                new ClientCommand.Deregister("client-1", timestamp),
                new ClientCommand.UpdateStatus("client-1", ClientStatus.HEALTHY, ClientStatus.DEGRADED, timestamp),
                new ClientCommand.UpdateCapabilities("client-1", capabilities, timestamp),
                new ClientCommand.Heartbeat("client-1", ClientStatus.DEGRADED, timestamp, 42));

        for (ClientCommand command : commands) {
            ClientCommand decoded = ClientCodec.fromProto(ClientCodec.toProto(command));
            assertEquals(command.getClass(), decoded.getClass());
            assertEquals(command.clientId(), decoded.clientId());
            assertEquals(timestamp, decoded.timestamp());
        }

        ClientCommand.Register register = (ClientCommand.Register) ClientCodec.fromProto(ClientCodec.toProto(commands.getFirst()));
        assertEquals("host", register.clientInfo().getHostname());
        assertEquals(Set.of("kv", "health"), register.clientInfo().getCapabilities().getSupportedServices());
        assertEquals(ClientStatus.HEALTHY, register.clientInfo().getStatus());
        ClientCommand.Heartbeat heartbeat = (ClientCommand.Heartbeat)
                ClientCodec.fromProto(ClientCodec.toProto(commands.getLast()));
        assertEquals(42, heartbeat.sequenceNumber());
    }

    @Test
    void rejectsUnspecifiedCommandType() {
        ClientCommandProto proto = ClientCommandProto.newBuilder().setClientId("client").build();
        assertThrows(IllegalArgumentException.class, () -> ClientCodec.fromProto(proto));
    }

    @Test
    void factoriesProduceTypedCommands() {
        ClientInfo info = new ClientInfo("client", "host", "address", 1);
        assertInstanceOf(ClientCommand.Register.class, ClientCommand.register(info));
        assertInstanceOf(ClientCommand.Deregister.class, ClientCommand.deregister("client"));
        assertInstanceOf(ClientCommand.Heartbeat.class, ClientCommand.heartbeat("client"));
        assertInstanceOf(ClientCommand.Heartbeat.class, ClientCommand.heartbeat("client", null, null));
    }

    /** Statuses inherited from the job system, which replicated history written earlier may still hold. */
    private static final Map<ClientStatusProto, ClientStatus> LEGACY_STATUSES = Map.of(
            ClientStatusProto.CLIENT_STATUS_ACTIVE, ClientStatus.HEALTHY,
            ClientStatusProto.CLIENT_STATUS_IDLE, ClientStatus.HEALTHY,
            ClientStatusProto.CLIENT_STATUS_OVERLOADED, ClientStatus.DEGRADED,
            ClientStatusProto.CLIENT_STATUS_DRAINING, ClientStatus.MAINTENANCE);

    @Test
    void legacyStatusesInReplicatedHistoryDecodeToTheirCurrentMeaning() {
        Instant timestamp = Instant.parse("2026-02-03T04:05:06Z");
        ClientCommandProto heartbeat = ClientCodec.toProto(
                new ClientCommand.Heartbeat("client-1", ClientStatus.HEALTHY, timestamp, 1));
        ClientCommandProto register = ClientCodec.toProto(new ClientCommand.Register("client-1",
                new ClientInfo("client-1", "host", "10.0.0.1", 8080), timestamp));

        LEGACY_STATUSES.forEach((legacy, current) -> {
            ClientCommand.Heartbeat decodedHeartbeat = (ClientCommand.Heartbeat) ClientCodec.fromProto(
                    heartbeat.toBuilder().setNewStatus(legacy).build());
            ClientCommand.Register decodedRegister = (ClientCommand.Register) ClientCodec.fromProto(register.toBuilder()
                    .setClientInfo(register.getClientInfo().toBuilder().setStatus(legacy)).build());
            assertEquals(current, decodedHeartbeat.status(), legacy.name());
            assertEquals(current, decodedRegister.clientInfo().getStatus(), legacy.name());
        });
    }

    @Test
    void noCurrentStatusIsEncodedAsALegacyValue() {
        Instant timestamp = Instant.parse("2026-02-03T04:05:06Z");
        for (ClientStatus status : EnumSet.allOf(ClientStatus.class)) {
            ClientStatusProto encoded = ClientCodec.toProto(
                    new ClientCommand.Heartbeat("client-1", status, timestamp, 1)).getNewStatus();
            assertFalse(LEGACY_STATUSES.containsKey(encoded), status + " encodes as " + encoded);
        }
    }
}
