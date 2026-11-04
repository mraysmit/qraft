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
import dev.mars.qraft.raft.RaftCommand;
import dev.mars.qraft.state.acl.AclBuiltins;
import dev.mars.qraft.state.catalog.ServiceCheckId;
import dev.mars.qraft.state.catalog.ServiceHealth;
import dev.mars.qraft.state.catalog.ServiceInstance;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that a snapshot written before ACLs restores with its nodes, services, checks and metadata and the initial
 * ACL state, and writes the same snapshot again; that the log entries frozen before ACLs still decode as what they
 * were; and that the fixture's bytes stay unchanged. The manifest beside the fixture lists what it holds.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-04
 * @version 1.0
 */
class AclFixtureTest {
    private static final String FIXTURE_ROOT = "/fixtures/acl/";
    private static final Instant REGISTERED = Instant.parse("2026-11-04T08:00:00Z");
    private static final ServiceInstance API_1 = new ServiceInstance("api-1", "api", "node-web", "10.0.1.5", 8080,
            List.of("v2"), Map.of("owner", "team-a"), ServiceHealth.PASSING, "platform", "payments", "dc-1",
            "eu-west", true);
    private static final Map<String, String> SHA_256 = Map.of(
            "snapshot-before-acl.json", "65584efbc8ab8444ad34cb0fdf6975b411dbf8fb314f38ed6017198c824dca55");
    /** The entry fixtures written before ACLs, by earlier lists, which must decode as the commands they were. */
    private static final List<String> ENTRIES_BEFORE_ACL = List.of(
            "/fixtures/node/node-register-rich.bin", "/fixtures/node/node-heartbeat.bin",
            "/fixtures/node/node-deregister.bin", "/fixtures/catalog/register-rich.bin",
            "/fixtures/catalog/deregister.bin", "/fixtures/node-identity/node-register-before-node-id.bin");

    private final ProtobufRaftCommandCodec codec = new ProtobufRaftCommandCodec();

    @Test
    void restoresASnapshotWrittenBeforeAclsWithTheInitialAclState() throws Exception {
        QraftStateStore store = new QraftStateStore();

        store.restoreSnapshot(fixture(FIXTURE_ROOT + "snapshot-before-acl.json"));

        assertEquals(5, store.getLastAppliedIndex());
        Node web = store.findNode("node-web").orElseThrow();
        assertEquals("6f1c2a3b-4d5e-4f60-8a7b-9c0d1e2f3a4b", web.id());
        assertEquals(NodeStatus.HEALTHY, web.status());
        assertEquals(REGISTERED.plusSeconds(5), web.lastHeartbeat());
        assertEquals(List.of(API_1), store.getServiceCatalog().instances());
        assertTrue(store.findHealthCheck(new ServiceCheckId(API_1.identity(), "http")).isPresent());
        assertEquals("on", store.getMetadata("feature"));

        assertEquals(AclBuiltins.policies(), store.getAcl().policies());
        assertEquals(List.of(AclBuiltins.anonymousToken()), store.getAcl().tokens());
        assertEquals(List.of(), store.getAcl().roles());
        assertEquals(0, store.getAcl().bootstrapIndex(), "a cluster from before ACLs has not been bootstrapped");

        byte[] again = store.takeSnapshot();
        QraftStateStore reopened = new QraftStateStore();
        reopened.restoreSnapshot(again);
        assertArrayEquals(again, reopened.takeSnapshot(), "the restored state writes the same snapshot again");
    }

    @Test
    void entriesWrittenBeforeAclsDecodeAsTheCommandsTheyWere() throws Exception {
        for (String entry : ENTRIES_BEFORE_ACL) {
            RaftCommand decoded = codec.deserialize(fixture(entry));

            assertNotNull(decoded, entry);
            assertFalse(decoded instanceof AclCommand, entry);
            assertTrue(Set.of(NodeCommand.class, CatalogCommand.class).stream()
                    .anyMatch(type -> type.isInstance(decoded)), entry + " decodes as " + decoded);
        }
    }

    @Test
    void fixtureBytesRemainImmutable() throws Exception {
        for (var expected : SHA_256.entrySet()) {
            String actual = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(fixture(FIXTURE_ROOT + expected.getKey())));
            assertEquals(expected.getValue(), actual, expected.getKey());
        }
    }

    private static byte[] fixture(String path) throws IOException {
        try (InputStream input = AclFixtureTest.class.getResourceAsStream(path)) {
            assertNotNull(input, "missing immutable fixture " + path);
            return input.readAllBytes();
        }
    }
}
