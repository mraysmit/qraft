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

/**
 * One consistent view of a node's Raft state, captured on its state loop. Its indexes belong to the same
 * moment: {@code snapshotLastIndex <= lastApplied <= commitIndex <= lastLogIndex} holds. {@code running} is
 * false until recovery has finished and again once the node begins to stop.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-27
 * @version 1.0
 */
public record RaftStatus(String nodeId, RaftNode.State state, long term, String leaderId, long commitIndex,
                         long lastApplied, long lastLogIndex, long snapshotLastIndex, boolean fenced,
                         boolean running) {

    /** True when this node is the leader, or a follower that knows its current leader. */
    public boolean knowsLeader() {
        return state == RaftNode.State.LEADER
                || (state == RaftNode.State.FOLLOWER && leaderId != null && !leaderId.isBlank());
    }
}
