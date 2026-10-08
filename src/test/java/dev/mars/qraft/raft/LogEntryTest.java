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

package dev.mars.qraft.raft;

import dev.mars.qraft.state.DistributedStateRaftCommand;
import dev.mars.qraft.state.distributed.DistributedStateCommand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link LogEntry}: it keeps its term, index and command, copies a replicated payload so the caller's
 * array cannot change it, and is a no-op exactly when it carries no command.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-28
 * @version 1.0
 */
class LogEntryTest {

    @Test
    void anEntryKeepsItsFieldsCopiesItsPayloadAndIsANoOpOnlyWithoutACommand() {
        RaftCommand command = new DistributedStateRaftCommand(DistributedStateCommand.put("test", "value"));
        LogEntry entry = new LogEntry(1, 5, command);

        assertEquals(1, entry.getTerm());
        assertEquals(5, entry.getIndex());
        assertEquals(command, entry.getCommand());
        assertNull(entry.getPayload());
        byte[] payload = {1, 2, 3};
        LogEntry replicated = new LogEntry(1, 5, command, payload);
        payload[0] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, replicated.getPayload(), "the payload is copied defensively");
        assertFalse(entry.isNoOp());
        assertTrue(new LogEntry(1, 6, null).isNoOp());
    }
}
