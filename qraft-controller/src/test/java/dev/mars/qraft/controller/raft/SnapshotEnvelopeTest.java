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

package dev.mars.qraft.controller.raft;

import dev.mars.qraft.controller.raft.RaftConfiguration.Server;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link SnapshotEnvelope}, which stores beside a state machine snapshot the configuration that snapshot
 * covers: both come back intact, an empty state snapshot included; a snapshot from before configurations were
 * recorded is refused with the reason; and a damaged envelope is refused rather than read.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-29
 * @version 1.0
 */
class SnapshotEnvelopeTest {
    private static final RaftConfiguration CONFIGURATION = new RaftConfiguration(List.of(
            new Server("id-a", "a", "a:9080", true), new Server("id-b", "b", "b:9080", false)));

    @Test
    void theConfigurationAndTheStateSnapshotComeBackIntact() {
        byte[] state = "{\"metadata\":{\"k\":\"v\"}}".getBytes(StandardCharsets.UTF_8);

        SnapshotEnvelope read = SnapshotEnvelope.unwrap(SnapshotEnvelope.wrap(CONFIGURATION, state));

        assertEquals(CONFIGURATION, read.configuration());
        assertArrayEquals(state, read.stateMachineSnapshot());
        assertArrayEquals(new byte[0],
                SnapshotEnvelope.unwrap(SnapshotEnvelope.wrap(CONFIGURATION, new byte[0])).stateMachineSnapshot());
    }

    @Test
    void aSnapshotFromBeforeConfigurationsWereRecordedIsRefused() {
        byte[] legacy = "{\"metadata\":{}}".getBytes(StandardCharsets.UTF_8);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> SnapshotEnvelope.unwrap(legacy));

        assertTrue(refused.getMessage().contains("records no cluster configuration"), refused.getMessage());
    }

    @Test
    void aDamagedEnvelopeIsRefused() {
        byte[] whole = SnapshotEnvelope.wrap(CONFIGURATION, "state".getBytes(StandardCharsets.UTF_8));

        for (int length : List.of(5, 9, 12)) {
            byte[] cut = Arrays.copyOf(whole, length);
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> SnapshotEnvelope.unwrap(cut), "cut to " + length + " bytes");
            assertTrue(refused.getMessage().startsWith("Damaged snapshot envelope"), refused.getMessage());
        }
    }
}
