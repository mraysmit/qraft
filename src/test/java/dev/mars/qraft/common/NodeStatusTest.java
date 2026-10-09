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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests {@link NodeStatus}: the three statuses Qraft sets, strict parsing of API input, and the reading of the
 * removed statuses that replicated JSON written by earlier versions may hold.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-09
 * @version 1.0
 */
class NodeStatusTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void theStatusesAreTheThreeQraftSets() {
        assertEquals(List.of(NodeStatus.REGISTERING, NodeStatus.HEALTHY, NodeStatus.UNREACHABLE),
                List.of(NodeStatus.values()));
        assertEquals(List.of("registering", "healthy", "unreachable"),
                List.of(NodeStatus.values()).stream().map(NodeStatus::getValue).toList());
    }

    @Test
    void aStatusIsWrittenAsItsValue() throws Exception {
        assertEquals("\"unreachable\"", JSON.writeValueAsString(NodeStatus.UNREACHABLE));
        assertEquals("healthy", NodeStatus.HEALTHY.toString());
    }

    @Test
    void apiInputIsParsedStrictlyAndCaseInsensitively() {
        assertEquals(NodeStatus.HEALTHY, NodeStatus.fromValue("healthy"));
        assertEquals(NodeStatus.UNREACHABLE, NodeStatus.fromValue("UnReachable"));

        assertThrows(IllegalArgumentException.class, () -> NodeStatus.fromValue(null));
        assertThrows(IllegalArgumentException.class, () -> NodeStatus.fromValue("unknown"));
        for (String removed : List.of("active", "idle", "degraded", "overloaded", "maintenance", "draining",
                "failed", "deregistered")) {
            assertThrows(IllegalArgumentException.class, () -> NodeStatus.fromValue(removed), removed);
        }
    }

    @Test
    void replicatedJsonHoldingARemovedStatusReadsAsWhetherTheNodeWasInContact() throws Exception {
        Map<String, NodeStatus> stored = Map.ofEntries(
                Map.entry("registering", NodeStatus.REGISTERING),
                Map.entry("healthy", NodeStatus.HEALTHY),
                Map.entry("unreachable", NodeStatus.UNREACHABLE),
                Map.entry("active", NodeStatus.HEALTHY),
                Map.entry("idle", NodeStatus.HEALTHY),
                Map.entry("degraded", NodeStatus.HEALTHY),
                Map.entry("overloaded", NodeStatus.HEALTHY),
                Map.entry("maintenance", NodeStatus.HEALTHY),
                Map.entry("draining", NodeStatus.HEALTHY),
                Map.entry("failed", NodeStatus.UNREACHABLE),
                Map.entry("deregistered", NodeStatus.UNREACHABLE));

        for (var entry : stored.entrySet()) {
            assertEquals(entry.getValue(), JSON.readValue('"' + entry.getKey() + '"', NodeStatus.class),
                    entry.getKey());
            assertEquals(entry.getValue(), NodeStatus.fromStoredValue(entry.getKey().toUpperCase()),
                    "stored values read whatever their case: " + entry.getKey());
        }
        assertThrows(IllegalArgumentException.class, () -> NodeStatus.fromStoredValue("unknown"));
    }
}
