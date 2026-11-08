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

package dev.mars.qraft.state.session;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * One session (sessions list, Phase 1; design section 11), as Consul's: an ID, the node it is bound to, a name, what
 * becomes of its keys when it is invalidated, its TTL, which zero means it has none, and the lock delay its keys keep
 * after it is invalidated.
 *
 * @param id          the session's ID, chosen by the server that proposes its creation
 * @param node        the name of the node the session is bound to
 * @param name        a name for people, possibly empty
 * @param behavior    whether its keys are released or deleted when it is invalidated
 * @param ttl         its TTL, or zero for none
 * @param lockDelay   how long its keys cannot be acquired after it is invalidated
 * @param createIndex the Raft index at which it was created; 0 in a command, set as it applies
 * @param createTime  when the leader proposed it
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public record Session(String id, String node, String name, SessionBehavior behavior, Duration ttl, Duration lockDelay,
                      long createIndex, Instant createTime) {
    public Session {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("a session needs an ID");
        if (node == null || node.isBlank()) throw new IllegalArgumentException("a session is bound to a node");
        name = name == null ? "" : name;
        Objects.requireNonNull(behavior, "behavior");
        ttl = ttl == null ? Duration.ZERO : ttl;
        lockDelay = lockDelay == null ? Duration.ZERO : lockDelay;
        if (ttl.isNegative() || lockDelay.isNegative()) throw new IllegalArgumentException("durations are not negative");
        Objects.requireNonNull(createTime, "createTime");
    }

    /** This session as created at {@code index}. */
    public Session createdAt(long index) {
        return new Session(id, node, name, behavior, ttl, lockDelay, index, createTime);
    }
}
