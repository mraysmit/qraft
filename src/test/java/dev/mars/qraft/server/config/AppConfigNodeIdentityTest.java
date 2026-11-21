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

package dev.mars.qraft.server.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests that {@link AppConfig} takes an explicit node ID only from the document and allows a
 * host-derived ID for a single node.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-03-15
 * @version 1.0
 */
class AppConfigNodeIdentityTest {
    @Test
    void explicitNodeIdentityComesOnlyFromTheDocument() {
        System.setProperty("qraft.node.id", "must-not-win");
        try {
            AppConfig config = AppConfig.fromJson("""
                    {"version":1,"server":{"id":"document-node"}}
                    """);
            assertEquals("document-node", config.getNodeId());
        } finally {
            System.clearProperty("qraft.node.id");
        }
    }

    /**
     * Code defects list, item 15: a server finds its own Raft address under its name in {@code server.raft.nodes};
     * one not listed there has no address to advertise and would count every listed server as a peer.
     */
    @Test
    void aServerMustBeListedInItsOwnNodeList() {
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> AppConfig.fromJson("""
                {"version":1,"server":{"id":"c","raft":{"nodes":{"a":"a:9080","b":"b:9080"}}}}
                """).validate());
        assertEquals("server.id 'c' is not a key of server.raft.nodes, which lists: a, b", refused.getMessage());
        assertDoesNotThrow(() -> AppConfig.fromJson("""
                {"version":1,"server":{"id":"b","raft":{"nodes":{"a":"a:9080","b":"b:9080"}}}}
                """).validate());
    }

    @Test
    void singleNodeMayUseAHostDerivedIdentity() {
        AppConfig config = AppConfig.fromJson("{\"version\":1,\"server\":{}}");
        assertFalse(config.getNodeId().isBlank());
    }
}
