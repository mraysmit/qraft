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

import java.time.Instant;

/**
 * A server's log and snapshot state, captured on its state loop (UI-3 task list, decision 6).
 *
 * @param snapshotLastIndex    the last index the latest snapshot holds, 0 before any
 * @param snapshotLastTerm     that index's term
 * @param firstLogIndex        the first index the log still holds: the entries before it are compacted into the
 *                             snapshot. It is one past the last log index when the log holds none
 * @param lastLogIndex         the last index in the log
 * @param commitIndex          the commit index
 * @param lastApplied          the last index applied to the state machine
 * @param fenced               whether the storage is fenced, so nothing more is written
 * @param fenceReason          why, or null
 * @param transitionQueueDepth the durable transitions queued or running
 * @param lastSnapshotFailure  the last snapshot that failed since the server started, or null
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-14
 * @version 1.0
 */
public record RaftStorageState(long snapshotLastIndex, long snapshotLastTerm, long firstLogIndex, long lastLogIndex,
                               long commitIndex, long lastApplied, boolean fenced, String fenceReason,
                               int transitionQueueDepth, SnapshotFailure lastSnapshotFailure) {

    /** A snapshot that failed: why, and when. */
    public record SnapshotFailure(String message, Instant at) {
    }
}
