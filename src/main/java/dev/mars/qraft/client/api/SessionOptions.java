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

package dev.mars.qraft.client.api;

import java.time.Duration;
import java.util.Objects;

/**
 * What a session is created with (client library list, decision 4): its name; the node it is bound to, which a
 * client's local API fills with its own and a server requires; its TTL, none by default; its lock delay, the server's
 * 15 seconds by default; and its behaviour when it ends.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public record SessionOptions(String name, String node, Duration ttl, Duration lockDelay, Behavior behavior) {
    /** What happens to a session's keys when it ends: they are released, or deleted. */
    public enum Behavior {
        RELEASE, DELETE;

        String apiName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public SessionOptions {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(behavior, "behavior");
    }

    public static SessionOptions named(String name) {
        return new SessionOptions(name, null, null, null, Behavior.RELEASE);
    }

    public SessionOptions node(String node) {
        return new SessionOptions(name, Objects.requireNonNull(node, "node"), ttl, lockDelay, behavior);
    }

    /** A TTL of 10 seconds to 24 hours: the session ends unless it is renewed within it. */
    public SessionOptions ttl(Duration ttl) {
        return new SessionOptions(name, node, Objects.requireNonNull(ttl, "ttl"), lockDelay, behavior);
    }

    /** How long after the session ends before another can acquire a key it held, at most 60 seconds. */
    public SessionOptions lockDelay(Duration lockDelay) {
        return new SessionOptions(name, node, ttl, Objects.requireNonNull(lockDelay, "lockDelay"), behavior);
    }

    public SessionOptions behavior(Behavior behavior) {
        return new SessionOptions(name, node, ttl, lockDelay, behavior);
    }
}
