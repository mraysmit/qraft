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
 * Tests {@link ClientStatus}: its values and categories, strict parsing of API input, lenient reading of
 * replicated JSON written while the job system's statuses existed, and the absence of those statuses.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-03-15
 * @version 2.0
 */
class ClientStatusTest {

    @Test
    void theStatusesAreTheNodeLifecycle() {
        assertEquals(List.of(ClientStatus.REGISTERING, ClientStatus.HEALTHY, ClientStatus.DEGRADED,
                ClientStatus.MAINTENANCE, ClientStatus.UNREACHABLE, ClientStatus.FAILED, ClientStatus.DEREGISTERED),
                List.of(ClientStatus.values()));
        for (ClientStatus status : ClientStatus.values()) {
            assertEquals(status.name().toLowerCase(Locale.ROOT), status.getValue());
            assertEquals(status.getValue(), status.toString());
            assertFalse(status.getDescription().isBlank());
        }
    }

    @Test
    void onlyHealthyAndDegradedNodesAreOperational() {
        Set<ClientStatus> operational = Arrays.stream(ClientStatus.values()).filter(ClientStatus::isOperational)
                .collect(Collectors.toSet());

        assertEquals(Set.of(ClientStatus.HEALTHY, ClientStatus.DEGRADED), operational);
    }

    @Test
    void categoriesPartitionTheStatuses() {
        assertEquals(Set.of(ClientStatus.HEALTHY), matching(ClientStatus::isHealthy));
        assertEquals(Set.of(ClientStatus.DEGRADED, ClientStatus.UNREACHABLE, ClientStatus.FAILED),
                matching(ClientStatus::isProblematic));
        assertEquals(Set.of(ClientStatus.REGISTERING, ClientStatus.MAINTENANCE), matching(ClientStatus::isTransitional));
        assertEquals(Set.of(ClientStatus.DEREGISTERED), matching(ClientStatus::isTerminal));
        assertFalse(ClientStatus.FAILED.isTerminal(), "a failed node can still be deregistered");
    }

    @Test
    void apiInputIsParsedStrictlyAndCaseInsensitively() {
        assertEquals(ClientStatus.HEALTHY, ClientStatus.fromValue("healthy"));
        assertEquals(ClientStatus.HEALTHY, ClientStatus.fromValue("HEALTHY"));
        assertEquals(ClientStatus.MAINTENANCE, ClientStatus.fromValue("Maintenance"));
        assertThrows(IllegalArgumentException.class, () -> ClientStatus.fromValue("invalid-status"));
        assertThrows(IllegalArgumentException.class, () -> ClientStatus.fromValue(null));
    }

    @Test
    void theJobSystemsWorkSchedulingStatusesAreGone() {
        Set<String> names = Arrays.stream(ClientStatus.values()).map(Enum::name).collect(Collectors.toSet());
        for (String legacy : List.of("ACTIVE", "IDLE", "OVERLOADED", "DRAINING")) {
            assertFalse(names.contains(legacy), legacy);
            assertThrows(IllegalArgumentException.class,
                    () -> ClientStatus.fromValue(legacy.toLowerCase(Locale.ROOT)),
                    "an API caller can no longer send " + legacy);
        }
    }

    @Test
    void replicatedJsonWrittenWithTheJobSystemsStatusesReadsAsTheirCurrentMeaning() throws Exception {
        ObjectMapper json = new ObjectMapper();
        Map<String, ClientStatus> legacy = Map.of(
                "active", ClientStatus.HEALTHY,
                "idle", ClientStatus.HEALTHY,
                "overloaded", ClientStatus.DEGRADED,
                "draining", ClientStatus.MAINTENANCE);

        legacy.forEach((stored, current) -> {
            assertEquals(current, ClientStatus.fromStoredValue(stored), stored);
            assertEquals(current, ClientStatus.fromStoredValue(stored.toUpperCase(Locale.ROOT)), stored);
        });
        assertEquals(ClientStatus.DEGRADED, json.readValue("\"overloaded\"", ClientStatus.class));
        assertEquals(ClientStatus.UNREACHABLE, json.readValue("\"unreachable\"", ClientStatus.class));
        assertEquals("\"healthy\"", json.writeValueAsString(ClientStatus.HEALTHY), "a current value is written");
        assertThrows(IllegalArgumentException.class, () -> ClientStatus.fromStoredValue("unknown"));
    }

    private static Set<ClientStatus> matching(java.util.function.Predicate<ClientStatus> predicate) {
        return Arrays.stream(ClientStatus.values()).filter(predicate).collect(Collectors.toSet());
    }
}
