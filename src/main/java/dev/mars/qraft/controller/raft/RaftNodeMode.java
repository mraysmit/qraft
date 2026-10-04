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

import dev.mars.qraft.raft.api.SnapshotStore;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Describes how a {@link RaftNode} persists its state.
 *
 * <ul>
 *   <li>{@link Volatile} — in-memory only; all state is lost on restart.
 *       Suitable for tests and single-node development.</li>
 *   <li>{@link Durable} — backed by the external WAL contract and a separate
 *       application snapshot store;
 *       state survives restarts. Required for production.</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * // Volatile mode (tests)
 * RaftNode.builder()
 *         .runtime(runtime).nodeId(id).clusterNodes(nodes)
 *         .transport(transport).stateMachine(sm)
 *         .mode(RaftNodeMode.volatileMode())
 *         .electionTimeout(1000).heartbeatInterval(200).build();
 *
 * // Durable mode (production)
 * RaftNode.builder()
 *         .runtime(runtime).nodeId(id).clusterNodes(nodes)
 *         .transport(transport).stateMachine(sm)
 *         .mode(RaftNodeMode.durable(wal, snapshots))
 *         .electionTimeout(1000).heartbeatInterval(200).build();
 * }</pre>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-02-22
 */
public sealed interface RaftNodeMode {

    /**
     * In-memory only — no WAL, no persistence. All state is lost on restart.
     */
    record Volatile() implements RaftNodeMode {}

    /**
     * Backed by a write-ahead log. State survives restarts.
     *
     * @param raftStorage the WAL storage implementation (never null)
     */
    record Durable(dev.mars.raftlog.storage.RaftStorage raftStorage,
                   SnapshotStore snapshotStore) implements RaftNodeMode {
        public Durable {
            requireNonNull(raftStorage, "raftStorage must not be null");
            requireNonNull(snapshotStore, "snapshotStore must not be null");
        }
    }

    // ── Factory methods ──────────────────────────────────────────────

    /** Creates a volatile (in-memory only) mode. */
    static RaftNodeMode volatileMode() {
        return new Volatile();
    }

    /** Creates a durable mode backed by the given WAL storage. */
    static RaftNodeMode durable(dev.mars.raftlog.storage.RaftStorage storage,
                                SnapshotStore snapshotStore) {
        return new Durable(storage, snapshotStore);
    }

    // ── Query methods ────────────────────────────────────────────────

    /** Returns the storage if this is a durable mode, empty otherwise. */
    default Optional<dev.mars.raftlog.storage.RaftStorage> storage() {
        return switch (this) {
            case Durable d -> Optional.of(d.raftStorage());
            case Volatile v -> Optional.empty();
        };
    }

    /** Returns the application snapshot store in durable mode. */
    default Optional<SnapshotStore> snapshots() {
        return switch (this) {
            case Durable d -> Optional.of(d.snapshotStore());
            case Volatile v -> Optional.empty();
        };
    }

    /** Returns {@code true} if this mode has durable storage. */
    default boolean isDurable() {
        return this instanceof Durable;
    }

}
