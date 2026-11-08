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

package dev.mars.qraft.state.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mars.qraft.common.Node;
import dev.mars.qraft.raft.RaftCommand;
import dev.mars.qraft.state.CatalogCommand;
import dev.mars.qraft.state.NodeCommand;
import dev.mars.qraft.state.QraftStateStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests a service instance's indexes (catalog indexes list, Phase 1, decisions 1 and 3), as Consul's {@code CreateIndex}
 * and {@code ModifyIndex}: the entry that first registered it, and the one that last changed it; a registration that
 * changes nothing, or only the health it carries, leaves them; deregistration forgets them; and the snapshot carries
 * them, one written before them restoring 0.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class ServiceCatalogIndexTest {
    private final QraftStateStore store = new QraftStateStore();
    private long index;

    private long apply(RaftCommand command) {
        long at = index + 1;
        store.apply(command);
        store.setLastAppliedIndex(++index);
        return at;
    }

    private static ServiceInstance web(int port, ServiceHealth health) {
        return new ServiceInstance("web-1", "web", "host-a", "10.0.0.1", port, List.of(), Map.of(), health,
                "default", "default", "", "", true);
    }

    private ServiceCatalogView.Indexes indexes() {
        return store.getServiceCatalog().indexesOf(web(8080, ServiceHealth.PASSING).identity());
    }

    @Test
    void anInstanceKeepsTheIndexThatRegisteredItAndTheOneThatLastChangedIt() throws Exception {
        apply(NodeCommand.register(Node.of("host-a", "10.0.0.1", null, null, Map.of()), Instant.EPOCH));
        long registered = apply(CatalogCommand.register(web(8080, ServiceHealth.PASSING)));
        assertEquals(new ServiceCatalogView.Indexes(registered, registered), indexes());

        apply(CatalogCommand.register(web(8080, ServiceHealth.PASSING)));
        apply(CatalogCommand.register(web(8080, ServiceHealth.WARNING)));
        assertEquals(new ServiceCatalogView.Indexes(registered, registered), indexes(),
                "nothing changed but the health it carries");

        long changed = apply(CatalogCommand.register(web(9090, ServiceHealth.PASSING)));
        assertEquals(new ServiceCatalogView.Indexes(registered, changed), indexes());

        QraftStateStore restored = new QraftStateStore();
        restored.restoreSnapshot(store.takeSnapshot());
        assertEquals(indexes(), restored.getServiceCatalog().indexesOf(web(9090, ServiceHealth.PASSING).identity()));
        ObjectNode older = (ObjectNode) new ObjectMapper().readTree(store.takeSnapshot());
        older.remove("serviceIndexes");
        QraftStateStore fromOlder = new QraftStateStore();
        fromOlder.restoreSnapshot(new ObjectMapper().writeValueAsBytes(older));
        assertEquals(new ServiceCatalogView.Indexes(0, 0), fromOlder.getServiceCatalog().indexesOf(
                web(9090, ServiceHealth.PASSING).identity()));

        apply(CatalogCommand.deregister(web(9090, ServiceHealth.PASSING).identity()));
        assertEquals(new ServiceCatalogView.Indexes(0, 0), indexes(), "a deregistered instance has none");
        long again = apply(CatalogCommand.register(web(8080, ServiceHealth.PASSING)));
        assertEquals(new ServiceCatalogView.Indexes(again, again), indexes(), "registered anew");
    }
}
