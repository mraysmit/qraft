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

import java.io.Serializable;
import java.util.Objects;
/**
 * Description for LogEntry
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @version 1.0
 * @since 2025-08-20
 */

public class LogEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    private final long term;
    private final long index;
    private final RaftCommand command;
    /** The replicated encoding of the command; {@code null} only for in-memory sentinels. */
    private final byte[] payload;

    /** Creates an entry without a replicated encoding, used for in-memory sentinels and tests. */
    public LogEntry(long term, long index, RaftCommand command) {
        this(term, index, command, null);
    }

    /**
     * Creates an entry whose {@code command} was decoded from {@code payload}. The payload, not a
     * re-encoding of the command, is what is persisted, sent to followers, and compared with incoming
     * entries, so an encoding that is not byte-stable can never look like a divergent log.
     */
    public LogEntry(long term, long index, RaftCommand command, byte[] payload) {
        this.term = term;
        this.index = index;
        this.command = command;
        this.payload = payload == null ? null : payload.clone();
    }

    public long getTerm() {
        return term;
    }

    public long getIndex() {
        return index;
    }

    public RaftCommand getCommand() {
        return command;
    }

    /** Returns a copy of the replicated encoding, or {@code null} for an in-memory sentinel. */
    public byte[] getPayload() {
        return payload == null ? null : payload.clone();
    }

    /**
     * Check if this is a no-op entry (used for heartbeats).
     */
    public boolean isNoOp() {
        return command == null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LogEntry logEntry = (LogEntry) o;
        return term == logEntry.term &&
               index == logEntry.index &&
               Objects.equals(command, logEntry.command);
    }

    @Override
    public int hashCode() {
        return Objects.hash(term, index, command);
    }

    @Override
    public String toString() {
        return "LogEntry{" +
                "term=" + term +
                ", index=" + index +
                ", command=" + command +
                '}';
    }
}
