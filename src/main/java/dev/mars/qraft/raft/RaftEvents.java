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

import dev.mars.qraft.common.events.QraftEvent;

import java.util.Objects;

/**
 * The Raft node's system events (events list, Phase 3, decision 4; architecture section 4.1), offered at the points the
 * node has already sequenced: leadership gained once the election is won, leadership lost when the node leaves the
 * leader's role, a snapshot once it is published, and fencing once, when the transition sequencer fences.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public final class RaftEvents {
    private RaftEvents() { }

    /** This node became the leader of {@code term}. */
    public record LeadershipGained(long term) implements QraftEvent {
        @Override
        public String type() {
            return "raft.leadership_gained";
        }
    }

    /** This node, the leader of {@code term}, stepped down to {@code nowState}. */
    public record LeadershipLost(long term, String nowState) implements QraftEvent {
        public LeadershipLost {
            Objects.requireNonNull(nowState, "nowState");
        }

        @Override
        public String type() {
            return "raft.leadership_lost";
        }
    }

    /** A snapshot through {@code lastIndex} of {@code lastTerm} was published: taken here, or installed from a leader. */
    public record SnapshotPublished(long lastIndex, long lastTerm, boolean installed) implements QraftEvent {
        @Override
        public String type() {
            return "raft.snapshot_published";
        }
    }

    /** The node fenced itself for {@code reason}, the first failure's type and message. */
    public record Fenced(String reason) implements QraftEvent {
        public Fenced {
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public String type() {
            return "raft.fenced";
        }
    }
}
