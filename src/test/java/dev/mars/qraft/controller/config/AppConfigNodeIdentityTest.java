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

package dev.mars.qraft.controller.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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

    @Test
    void singleNodeMayUseAHostDerivedIdentity() {
        AppConfig config = AppConfig.fromJson("{\"version\":1,\"server\":{}}");
        assertFalse(config.getNodeId().isBlank());
    }
}
