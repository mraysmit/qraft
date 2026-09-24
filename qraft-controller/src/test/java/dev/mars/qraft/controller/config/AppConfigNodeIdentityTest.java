package dev.mars.qraft.controller.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
