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

package dev.mars.qraft.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests {@link AgentStatus}: its values and categories, strict parsing of API input, lenient reading of
 * replicated JSON written while the job system's statuses existed, and the absence of those statuses.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-03-15
 * @version 2.0
 */
class AgentStatusTest {

    @Test
    void theStatusesAreTheNodeLifecycle() {
        assertEquals(List.of(AgentStatus.REGISTERING, AgentStatus.HEALTHY, AgentStatus.DEGRADED,
                AgentStatus.MAINTENANCE, AgentStatus.UNREACHABLE, AgentStatus.FAILED, AgentStatus.DEREGISTERED),
                List.of(AgentStatus.values()));
        for (AgentStatus status : AgentStatus.values()) {
            assertEquals(status.name().toLowerCase(Locale.ROOT), status.getValue());
            assertEquals(status.getValue(), status.toString());
            assertFalse(status.getDescription().isBlank());
        }
    }

    @Test
    void onlyHealthyAndDegradedNodesAreOperational() {
        Set<AgentStatus> operational = Arrays.stream(AgentStatus.values()).filter(AgentStatus::isOperational)
                .collect(Collectors.toSet());

        assertEquals(Set.of(AgentStatus.HEALTHY, AgentStatus.DEGRADED), operational);
    }

    @Test
    void categoriesPartitionTheStatuses() {
        assertEquals(Set.of(AgentStatus.HEALTHY), matching(AgentStatus::isHealthy));
        assertEquals(Set.of(AgentStatus.DEGRADED, AgentStatus.UNREACHABLE, AgentStatus.FAILED),
                matching(AgentStatus::isProblematic));
        assertEquals(Set.of(AgentStatus.REGISTERING, AgentStatus.MAINTENANCE), matching(AgentStatus::isTransitional));
        assertEquals(Set.of(AgentStatus.DEREGISTERED), matching(AgentStatus::isTerminal));
        assertFalse(AgentStatus.FAILED.isTerminal(), "a failed node can still be deregistered");
    }

    @Test
    void apiInputIsParsedStrictlyAndCaseInsensitively() {
        assertEquals(AgentStatus.HEALTHY, AgentStatus.fromValue("healthy"));
        assertEquals(AgentStatus.HEALTHY, AgentStatus.fromValue("HEALTHY"));
        assertEquals(AgentStatus.MAINTENANCE, AgentStatus.fromValue("Maintenance"));
        assertThrows(IllegalArgumentException.class, () -> AgentStatus.fromValue("invalid-status"));
        assertThrows(IllegalArgumentException.class, () -> AgentStatus.fromValue(null));
    }

    @Test
    void theJobSystemsWorkSchedulingStatusesAreGone() {
        Set<String> names = Arrays.stream(AgentStatus.values()).map(Enum::name).collect(Collectors.toSet());
        for (String legacy : List.of("ACTIVE", "IDLE", "OVERLOADED", "DRAINING")) {
            assertFalse(names.contains(legacy), legacy);
            assertThrows(IllegalArgumentException.class,
                    () -> AgentStatus.fromValue(legacy.toLowerCase(Locale.ROOT)),
                    "an API caller can no longer send " + legacy);
        }
    }

    @Test
    void replicatedJsonWrittenWithTheJobSystemsStatusesReadsAsTheirCurrentMeaning() throws Exception {
        ObjectMapper json = new ObjectMapper();
        Map<String, AgentStatus> legacy = Map.of(
                "active", AgentStatus.HEALTHY,
                "idle", AgentStatus.HEALTHY,
                "overloaded", AgentStatus.DEGRADED,
                "draining", AgentStatus.MAINTENANCE);

        legacy.forEach((stored, current) -> {
            assertEquals(current, AgentStatus.fromStoredValue(stored), stored);
            assertEquals(current, AgentStatus.fromStoredValue(stored.toUpperCase(Locale.ROOT)), stored);
        });
        assertEquals(AgentStatus.DEGRADED, json.readValue("\"overloaded\"", AgentStatus.class));
        assertEquals(AgentStatus.UNREACHABLE, json.readValue("\"unreachable\"", AgentStatus.class));
        assertEquals("\"healthy\"", json.writeValueAsString(AgentStatus.HEALTHY), "a current value is written");
        assertThrows(IllegalArgumentException.class, () -> AgentStatus.fromStoredValue("unknown"));
    }

    private static Set<AgentStatus> matching(java.util.function.Predicate<AgentStatus> predicate) {
        return Arrays.stream(AgentStatus.values()).filter(predicate).collect(Collectors.toSet());
    }
}
