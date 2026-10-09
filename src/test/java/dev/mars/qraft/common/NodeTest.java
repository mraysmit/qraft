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

package dev.mars.qraft.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests {@link Node}: what a client describes and what the servers record, its metadata, its JSON, and that
 * JSON written by earlier versions still reads with the removed fields ignored.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-09
 * @version 1.0
 */
class NodeTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Instant REGISTERED = Instant.parse("2026-10-09T08:00:00Z");
    private static final Instant CONTACTED = Instant.parse("2026-10-09T08:00:30Z");

    @Test
    void aNodeAsAClientDescribesItHasNoStatusAndNoTimes() {
        Node node = Node.of("web-01", "10.0.0.5", "dc-1", "eu-west", Map.of("rack", "r7"));

        assertEquals("web-01", node.name());
        assertEquals("10.0.0.5", node.address());
        assertEquals("dc-1", node.datacenter());
        assertEquals("eu-west", node.region());
        assertEquals(Map.of("rack", "r7"), node.metadata());
        assertNull(node.status());
        assertNull(node.registrationTime());
        assertNull(node.lastHeartbeat());
    }

    @Test
    void metadataIsNeverNullIsInKeyOrderAndCannotBeChanged() {
        assertEquals(Map.of(), Node.of("web-01", null, null, null, null).metadata());

        Map<String, String> given = new HashMap<>(Map.of("zone", "b", "rack", "r7"));
        Node node = Node.of("web-01", null, null, null, given);
        given.put("later", "ignored");

        assertEquals(List.of("rack", "zone"), List.copyOf(node.metadata().keySet()),
                "a later change to the caller's map does not reach the node, and the keys are in order");
        assertThrows(UnsupportedOperationException.class, () -> node.metadata().put("k", "v"));
    }

    @Test
    void addingMetadataLeavesTheOriginalNodeAsItWas() {
        Node node = Node.of("web-01", "10.0.0.5", "dc-1", "eu-west", Map.of("rack", "r7"));

        Node changed = node.withMetadata(Node.VERSION_METADATA_KEY, "1.0.0");

        assertEquals(Map.of("rack", "r7"), node.metadata());
        assertEquals(Map.of("rack", "r7", "qraft.version", "1.0.0"), changed.metadata());
        assertEquals(node.name(), changed.name());
        assertEquals(node.address(), changed.address());
    }

    @Test
    void aRegistrationIsRecordedAsRegisteringAtItsTimeWithNoHeartbeat() {
        Node claimed = new Node("web-01", "10.0.0.5", "dc-1", "eu-west", Map.of(),
                NodeStatus.HEALTHY, CONTACTED, CONTACTED);

        Node recorded = claimed.registeredAt(REGISTERED);

        assertEquals(NodeStatus.REGISTERING, recorded.status(), "a registration cannot claim a status");
        assertEquals(REGISTERED, recorded.registrationTime(), "a registration cannot claim its time");
        assertNull(recorded.lastHeartbeat(), "a registration cannot claim a heartbeat");
        assertEquals("10.0.0.5", recorded.address());
    }

    @Test
    void theLastContactIsTheLastHeartbeatOrElseTheRegistrationTime() {
        Node registered = Node.of("web-01", null, null, null, null).registeredAt(REGISTERED);

        assertEquals(REGISTERED, registered.lastContact());
        assertEquals(CONTACTED, registered.withLastHeartbeat(CONTACTED).lastContact());
        assertEquals(NodeStatus.HEALTHY, registered.withStatus(NodeStatus.HEALTHY).status());
    }

    @Test
    void nodesAreEqualExactlyWhenEveryFieldIs() {
        Node node = Node.of("web-01", "10.0.0.5", "dc-1", "eu-west", Map.of("rack", "r7"));

        assertEquals(node, Node.of("web-01", "10.0.0.5", "dc-1", "eu-west", Map.of("rack", "r7")));
        assertNotEquals(node, Node.of("web-01", "10.0.0.6", "dc-1", "eu-west", Map.of("rack", "r7")));
        assertNotEquals(node, node.withStatus(NodeStatus.HEALTHY));
    }

    @Test
    void jsonHoldsExactlyTheEightFieldsOfANodeAndReadsBack() throws Exception {
        Node node = new Node("web-01", "10.0.0.5", "dc-1", "eu-west", Map.of("rack", "r7"),
                NodeStatus.HEALTHY, REGISTERED, CONTACTED);

        JsonNode json = JSON.readTree(JSON.writeValueAsString(node));
        Set<String> fields = new java.util.TreeSet<>();
        json.fieldNames().forEachRemaining(fields::add);

        assertEquals(Set.of("name", "address", "datacenter", "region", "metadata", "status",
                "registrationTime", "lastHeartbeat"), fields);
        assertEquals("healthy", json.get("status").asText());
        assertEquals(node, JSON.readValue(JSON.writeValueAsString(node), Node.class));
    }

    @Test
    void jsonWrittenByEarlierVersionsReadsWithItsRemovedFieldsIgnored() throws Exception {
        String stored = """
                {"healthy":true,"endpoint":"http://192.0.2.10:8500","%s":"node-legacy","hostname":"legacy-host",
                 "address":"192.0.2.10","port":8500,"capabilities":{"supportedServices":["http"]},"status":"active",
                 "registrationTime":1790064000.000000000,"lastHeartbeat":null,"version":"2.9.0",
                 "region":"eu-west","datacenter":"dc-legacy","metadata":{"rack":"r1"}}
                """;
        Node expected = new Node("node-legacy", "192.0.2.10", "dc-legacy", "eu-west", Map.of("rack", "r1"),
                NodeStatus.HEALTHY, Instant.ofEpochSecond(1_790_064_000L), null);

        // The node's name under each of the two names it had before.
        assertEquals(expected, JSON.readValue(stored.formatted("clientId"), Node.class));
        assertEquals(expected, JSON.readValue(stored.formatted("agentId"), Node.class));
    }
}
