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

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * The replicated sessions (sessions list, Phase 1), by ID in ID order. Commands change it on the state machine's
 * thread; readers on others see each session whole.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public final class SessionState {
    private final ConcurrentSkipListMap<String, Session> sessions = new ConcurrentSkipListMap<>();

    /** Adds {@code session} at {@code index}; whether its ID was free. */
    public boolean create(Session session, long index) {
        return sessions.putIfAbsent(session.id(), session.createdAt(index)) == null;
    }

    /** Removes the session named {@code id}, if there is one. */
    public Optional<Session> destroy(String id) {
        return Optional.ofNullable(sessions.remove(id));
    }

    public Optional<Session> get(String id) {
        return Optional.ofNullable(sessions.get(id));
    }

    /** How many sessions exist. */
    public int size() {
        return sessions.size();
    }

    /** Every session, in ID order. */
    public List<Session> list() {
        return List.copyOf(sessions.values());
    }

    /** The sessions bound to {@code node}, in ID order. */
    public List<Session> ofNode(String node) {
        return sessions.values().stream().filter(session -> session.node().equals(node)).toList();
    }

    public List<Session> snapshot() {
        return list();
    }

    /** Replaces the sessions with {@code snapshot}'s; none, from a snapshot written before sessions. */
    public void restore(List<Session> snapshot) {
        sessions.clear();
        if (snapshot != null) snapshot.forEach(session -> sessions.put(session.id(), session));
    }
}
