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

import dev.mars.qraft.common.Node;
import dev.mars.qraft.common.NodeStatus;
import dev.mars.qraft.raft.RaftCommandResult;
import dev.mars.qraft.state.catalog.HealthObservation;
import dev.mars.qraft.state.catalog.ServiceCheckId;
import dev.mars.qraft.state.catalog.ServiceHealth;
import dev.mars.qraft.state.catalog.ServiceInstance;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the node ID in the replicated node registry and the rules it brings (client list decision 8, after
 * Consul's catalog): a name a node of another ID holds is refused while that node is registering or healthy,
 * names conflict regardless of case, an unreachable holder's name is taken over with what it registered, a
 * registration with an ID claims a node that has none and keeps what it registered, and a node whose ID returns
 * under a new name is removed under its old name with what it registered and inserted under the new one.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-29
 * @version 1.0
 */
class NodeIdentityStateStoreTest {
    private static final Instant REGISTERED = Instant.parse("2026-10-29T09:00:00Z");
    private static final String WEB_ID = "1b4e28ba-2fa1-4d2a-883f-0016d3cca427";
    private static final String OTHER_ID = "9c5b94b1-35ad-49bb-b118-8e8fc24abf80";

    private final QraftStateStore store = new QraftStateStore();

    @Test
    void aRegisteredNodeKeepsItsIdThroughASnapshot() {
        store.apply(NodeCommand.register(node("web-1").withId(WEB_ID), REGISTERED));
        assertEquals(WEB_ID, store.findNode("web-1").orElseThrow().id());

        QraftStateStore restored = new QraftStateStore();
        restored.restoreSnapshot(store.takeSnapshot());

        assertEquals(WEB_ID, restored.findNode("web-1").orElseThrow().id());
    }

    @Test
    void anotherIdIsRefusedTheNameOfANodeThatIsRegisteringOrHealthy() {
        register("web-1", WEB_ID);
        withServiceAndCheck("web-1");
        assertRefused(register("web-1", OTHER_ID), "web-1", WEB_ID);

        heartbeat("web-1");
        assertEquals(NodeStatus.HEALTHY, store.findNode("web-1").orElseThrow().status());
        assertRefused(register("web-1", OTHER_ID), "web-1", WEB_ID);
        assertTrue(hasServiceAndCheck("web-1"), "a refused registration changes nothing");
    }

    @Test
    void namesConflictRegardlessOfCase() {
        register("web-1", WEB_ID);

        assertRefused(register("Web-1", OTHER_ID), "web-1", WEB_ID);
        assertEquals(Set.of("web-1"), store.getNodes().keySet(), "no second node under another spelling");
    }

    @Test
    void anUnreachableHoldersNameIsTakenOverWithWhatItRegistered() {
        register("web-1", WEB_ID);
        withServiceAndCheck("web-1");
        markUnreachable("web-1");

        assertInstanceOf(RaftCommandResult.Success.class, register("web-1", OTHER_ID));

        Node holder = store.findNode("web-1").orElseThrow();
        assertEquals(OTHER_ID, holder.id());
        assertEquals(NodeStatus.REGISTERING, holder.status());
        assertFalse(hasService("web-1"), "the old node's services do not pass to the new ID");
        assertFalse(hasCheck("web-1"), "nor do its checks");
    }

    @Test
    void anUnreachableHoldersNameIsTakenOverUnderAnotherSpellingToo() {
        register("web-1", WEB_ID);
        withServiceAndCheck("web-1");
        markUnreachable("web-1");

        assertInstanceOf(RaftCommandResult.Success.class, register("Web-1", OTHER_ID));

        assertEquals(Set.of("Web-1"), store.getNodes().keySet());
        assertFalse(hasService("web-1"));
    }

    @Test
    void theOldIdIsRefusedAfterATakeoverWhileTheNewHolderHoldsTheName() {
        register("web-1", WEB_ID);
        markUnreachable("web-1");
        register("web-1", OTHER_ID);

        assertRefused(register("web-1", WEB_ID), "web-1", OTHER_ID);
    }

    @Test
    void aReapedNodesNameIsFreeForAnotherId() {
        register("web-1", WEB_ID);
        markUnreachable("web-1");
        store.apply(NodeCommand.expire("web-1", REGISTERED, true, REGISTERED.plusSeconds(60)));
        assertTrue(store.findNode("web-1").isEmpty());

        assertInstanceOf(RaftCommandResult.Success.class, register("web-1", OTHER_ID));
        assertEquals(OTHER_ID, store.findNode("web-1").orElseThrow().id());
    }

    @Test
    void aRegistrationWithAnIdClaimsANodeWithoutOneAndKeepsWhatItRegistered() {
        register("web-1", null);
        heartbeat("web-1");
        withServiceAndCheck("web-1");

        assertInstanceOf(RaftCommandResult.Success.class, register("web-1", WEB_ID));

        assertEquals(WEB_ID, store.findNode("web-1").orElseThrow().id());
        assertTrue(hasServiceAndCheck("web-1"), "the claimed node is that machine after its upgrade");
    }

    @Test
    void aRegistrationWithoutAnIdIsRefusedTheNameOfANodeWithOne() {
        register("web-1", WEB_ID);

        assertRefused(register("web-1", null), "web-1", WEB_ID);
    }

    @Test
    void theSameIdUnderTheSameNameKeepsWhatItRegistered() {
        register("web-1", WEB_ID);
        withServiceAndCheck("web-1");

        assertInstanceOf(RaftCommandResult.Success.class, register("web-1", WEB_ID));

        assertTrue(hasServiceAndCheck("web-1"), "a re-registration is not a takeover");
    }

    @Test
    void aRenameRemovesTheNodeUnderItsOldNameWithWhatItRegisteredAndInsertsItUnderTheNew() {
        register("web-1", WEB_ID);
        withServiceAndCheck("web-1");

        assertInstanceOf(RaftCommandResult.Success.class, register("web-2", WEB_ID));

        assertEquals(Set.of("web-2"), store.getNodes().keySet(), "the old name is gone");
        assertEquals(WEB_ID, store.findNode("web-2").orElseThrow().id());
        assertFalse(hasService("web-1"), "no service is left under the old name");
        assertFalse(hasCheck("web-1"), "nor any check");
    }

    @Test
    void aRenameToANameAnotherNodeHoldsIsRefusedAndChangesNothing() {
        register("web-1", WEB_ID);
        withServiceAndCheck("web-1");
        register("web-2", OTHER_ID);
        heartbeat("web-2");

        assertRefused(register("web-2", WEB_ID), "web-2", OTHER_ID);

        assertEquals(WEB_ID, store.findNode("web-1").orElseThrow().id());
        assertTrue(hasServiceAndCheck("web-1"));
        assertEquals(OTHER_ID, store.findNode("web-2").orElseThrow().id());
    }

    @Test
    void aRenameCannotClaimANameANodeWithoutAnIdHolds() {
        register("web-1", WEB_ID);
        register("web-2", null);

        assertRefused(register("web-2", WEB_ID), "web-2", null);
        assertEquals(WEB_ID, store.findNode("web-1").orElseThrow().id());
    }

    @Test
    void aRenameToANameWhoseHolderIsUnreachableTakesItOver() {
        register("web-1", WEB_ID);
        withServiceAndCheck("web-1");
        register("web-2", OTHER_ID);
        withServiceAndCheck("web-2");
        markUnreachable("web-2");

        assertInstanceOf(RaftCommandResult.Success.class, register("web-2", WEB_ID));

        assertEquals(Set.of("web-2"), store.getNodes().keySet());
        assertEquals(WEB_ID, store.findNode("web-2").orElseThrow().id());
        assertFalse(hasService("web-1"), "the renamed node's services left with its old name");
        assertFalse(hasService("web-2"), "and the unreachable holder's services left with it");
    }

    @Test
    void theSameIdUnderAnotherSpellingOfItsNameIsARename() {
        register("web-1", WEB_ID);
        withServiceAndCheck("web-1");

        assertInstanceOf(RaftCommandResult.Success.class, register("Web-1", WEB_ID));

        assertEquals(Set.of("Web-1"), store.getNodes().keySet());
        assertFalse(hasService("web-1"), "services are keyed by the exact name, so they leave with it");
    }

    private RaftCommandResult<?> register(String name, String id) {
        return store.apply(NodeCommand.register(node(name).withId(id), REGISTERED));
    }

    private void heartbeat(String name) {
        store.apply(NodeCommand.heartbeat(name, null, REGISTERED.plusSeconds(1)));
    }

    private void markUnreachable(String name) {
        Node current = store.findNode(name).orElseThrow();
        store.apply(NodeCommand.expire(name, current.lastContact(), false, REGISTERED.plusSeconds(30)));
        assertEquals(NodeStatus.UNREACHABLE, store.findNode(name).orElseThrow().status());
    }

    private void withServiceAndCheck(String node) {
        ServiceInstance instance = service(node);
        store.apply(CatalogCommand.register(instance, List.of("http")));
        store.apply(CatalogCommand.observe(new HealthObservation(new ServiceCheckId(instance.identity(), "http"),
                ServiceHealth.PASSING, 1, REGISTERED, 30_000, true, "ok"), REGISTERED));
    }

    private boolean hasService(String node) {
        return store.getServiceCatalog().find(service(node).identity()).isPresent();
    }

    private boolean hasCheck(String node) {
        return store.findHealthCheck(new ServiceCheckId(service(node).identity(), "http")).isPresent();
    }

    private boolean hasServiceAndCheck(String node) {
        return hasService(node) && hasCheck(node);
    }

    private void assertRefused(RaftCommandResult<?> result, String holderName, String holderId) {
        Node holder = assertInstanceOf(RaftCommandResult.CasMismatch.class, result,
                "refused with the node that holds the name").entity() instanceof Node node ? node : null;
        assertEquals(holderName, holder == null ? null : holder.name());
        assertEquals(holderId, holder == null ? null : holder.id());
        assertEquals(holderId, store.findNode(holderName).orElseThrow().id(), "the holder keeps its name");
    }

    private static ServiceInstance service(String node) {
        return new ServiceInstance("api", "api", node, "10.0.0.1", 8080, List.of(), Map.of(), ServiceHealth.UNKNOWN,
                "platform", "payments", "dc-1", "eu-west", true);
    }

    private static Node node(String name) {
        return Node.of(name, "10.0.0.1", "dc-1", "eu-west", Map.of());
    }
}
