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

package dev.mars.qraft.state.tenancy;

import dev.mars.qraft.common.Node;
import dev.mars.qraft.raft.RaftCommand;
import dev.mars.qraft.raft.RaftCommandResult;
import dev.mars.qraft.state.CatalogCommand;
import dev.mars.qraft.state.NodeCommand;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.state.catalog.ServiceHealth;
import dev.mars.qraft.state.catalog.ServiceInstance;
import dev.mars.qraft.state.kv.KvCommand;
import dev.mars.qraft.state.kv.KvScope;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the scoped write (tenancy list, Phase 2, decision 3): the state machine applies its command only when its tenant
 * and namespace exist, so a write that races a deletion cannot land in a deleted namespace; the same command without
 * the wrapper, as a log written before tenancy holds it, applies as it always did; and the wrapper round-trips through
 * the codec with the command it holds.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class ScopedWriteTest {
    private static final Instant T0 = Instant.parse("2026-11-08T12:00:00Z");
    private static final KvScope PROD = new KvScope("acme", "prod");
    private final QraftStateStore store = new QraftStateStore();
    private long index;

    private RaftCommandResult<?> apply(RaftCommand command) {
        RaftCommandResult<?> result = store.apply(command);
        store.setLastAppliedIndex(++index);
        return result;
    }

    private static void refused(String code, RaftCommandResult<?> result) {
        assertEquals(code, assertInstanceOf(RaftCommandResult.Refused.class, result).code(), result.toString());
    }

    private static ServiceInstance service(String tenant, String namespace) {
        return new ServiceInstance("web-1", "web", "host-a", "10.0.0.1", 8080, List.of(), Map.of(),
                ServiceHealth.PASSING, tenant, namespace, "", "", true);
    }

    @Test
    void aScopedWriteAppliesOnlyWhereItsTenantAndNamespaceExist() {
        apply(NodeCommand.register(Node.of("host-a", "10.0.0.1", null, null, Map.of()), T0));
        RaftCommand set = KvCommand.set(PROD, "config", bytes("v"), 0, null);

        refused("tenant_not_found", apply(ScopedWrite.of("acme", "prod", set)));
        apply(TenancyCommand.createTenant("acme", ""));
        refused("namespace_not_found", apply(ScopedWrite.of("acme", "prod", set)));
        refused("namespace_not_found", apply(ScopedWrite.of("acme", "prod",
                CatalogCommand.register(service("acme", "prod")))));
        assertTrue(store.getKv().get(PROD, "config").isEmpty());

        apply(TenancyCommand.createNamespace("acme", "prod", ""));
        assertEquals(new RaftCommandResult.Success<>(true), apply(ScopedWrite.of("acme", "prod", set)));
        assertInstanceOf(RaftCommandResult.Success.class, apply(ScopedWrite.of("acme", "prod",
                CatalogCommand.register(service("acme", "prod")))));
        assertTrue(store.getKv().get(PROD, "config").isPresent());
        assertEquals(1, store.getServiceCatalog().instances().size());

        apply(TenancyCommand.deleteNamespace("acme", "prod"));
        refused("namespace_not_found", apply(ScopedWrite.of("acme", "prod", set)),
                "a write proposed before the deletion and applied after it is refused");
    }

    @Test
    void anUnwrappedWriteAppliesAsALogFromBeforeTenancyHoldsIt() {
        assertEquals(new RaftCommandResult.Success<>(true), apply(KvCommand.set(PROD, "config", bytes("v"), 0, null)));
        assertTrue(store.getKv().get(PROD, "config").isPresent(), "replayed to the state it had before the upgrade");
    }

    @Test
    void theWrapperRoundTripsThroughTheCodecWithItsCommand() {
        ProtobufRaftCommandCodec codec = new ProtobufRaftCommandCodec();
        for (RaftCommand command : List.of(
                ScopedWrite.of("acme", "prod", KvCommand.set(PROD, "config", bytes("v"), 7, 3L)),
                ScopedWrite.of("acme", "prod", KvCommand.lock(PROD, "leader", bytes("me"), 0, "s1", T0)),
                ScopedWrite.of("acme", "prod", CatalogCommand.register(service("acme", "prod"), List.of("http"),
                        "1b4e28ba-2fa1-4d2a-883f-0016d3cca427")))) {
            assertEquals(command, codec.deserialize(codec.serialize(command)), command.toString());
        }
    }

    private static void refused(String code, RaftCommandResult<?> result, String message) {
        assertEquals(code, assertInstanceOf(RaftCommandResult.Refused.class, result, message).code(), message);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
