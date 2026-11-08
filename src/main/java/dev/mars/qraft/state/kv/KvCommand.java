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

package dev.mars.qraft.state.kv;

import dev.mars.qraft.raft.RaftCommand;

import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * A replicated key/value write (key/value list, Phase 1): a set or a delete, each with an optional compare-and-set
 * index, or a delete of every key under a prefix. Each answers whether it applied, as Consul's KV writes answer
 * {@code true} or {@code false}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public sealed interface KvCommand extends RaftCommand
        permits KvCommand.Set, KvCommand.Delete, KvCommand.DeleteTree, KvCommand.Lock, KvCommand.Unlock {
    KvScope scope();

    /**
     * Writes {@code key}.
     *
     * @param cas the entry's {@code modifyIndex} the write requires, 0 for an absent key, or {@code null} for none
     */
    record Set(KvScope scope, String key, byte[] value, long flags, Long cas) implements KvCommand {
        private static final long serialVersionUID = 1L;

        public Set {
            Objects.requireNonNull(scope, "scope");
            KvState.requireKey(key);
            value = value == null ? new byte[0] : value.clone();
            if (cas != null && cas < 0) throw new IllegalArgumentException("a compare-and-set index is not negative");
        }

        @Override
        public byte[] value() {
            return value.clone();
        }

        public OptionalLong casIndex() {
            return cas == null ? OptionalLong.empty() : OptionalLong.of(cas);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Set set && scope.equals(set.scope) && key.equals(set.key)
                    && Arrays.equals(value, set.value) && flags == set.flags && Objects.equals(cas, set.cas);
        }

        @Override
        public int hashCode() {
            return Objects.hash(scope, key, Arrays.hashCode(value), flags, cas);
        }

        @Override
        public String toString() {
            return "Set[" + scope + ":" + key + ", " + value.length + " bytes, flags=" + flags + ", cas=" + cas + "]";
        }
    }

    /**
     * Deletes {@code key}.
     *
     * @param cas the entry's {@code modifyIndex} the delete requires, or {@code null} for none
     */
    record Delete(KvScope scope, String key, Long cas) implements KvCommand {
        private static final long serialVersionUID = 1L;

        public Delete {
            Objects.requireNonNull(scope, "scope");
            KvState.requireKey(key);
            if (cas != null && cas < 0) throw new IllegalArgumentException("a compare-and-set index is not negative");
        }

        public OptionalLong casIndex() {
            return cas == null ? OptionalLong.empty() : OptionalLong.of(cas);
        }
    }

    /** Deletes every key of {@code scope} that starts with {@code prefix}. */
    record DeleteTree(KvScope scope, String prefix) implements KvCommand {
        private static final long serialVersionUID = 1L;

        public DeleteTree {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(prefix, "prefix");
        }
    }

    /**
     * Acquires {@code key} for {@code session}, writing its value, as Consul's {@code ?acquire} (sessions list, Phase 1);
     * {@code at} is the leader's time, which a lock delay is compared with (decision 3).
     */
    record Lock(KvScope scope, String key, byte[] value, long flags, String session, java.time.Instant at)
            implements KvCommand {
        private static final long serialVersionUID = 1L;

        public Lock {
            Objects.requireNonNull(scope, "scope");
            KvState.requireKey(key);
            value = value == null ? new byte[0] : value.clone();
            if (session == null || session.isBlank()) throw new IllegalArgumentException("acquire names a session");
            Objects.requireNonNull(at, "at");
        }

        @Override
        public byte[] value() {
            return value.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Lock lock && scope.equals(lock.scope) && key.equals(lock.key)
                    && Arrays.equals(value, lock.value) && flags == lock.flags && session.equals(lock.session)
                    && at.equals(lock.at);
        }

        @Override
        public int hashCode() {
            return Objects.hash(scope, key, Arrays.hashCode(value), flags, session, at);
        }

        @Override
        public String toString() {
            return "Lock[" + scope + ":" + key + ", " + value.length + " bytes, session=" + session + ", at=" + at + "]";
        }
    }

    /** Releases {@code key} if {@code session} holds it, writing its value, as Consul's {@code ?release}. */
    record Unlock(KvScope scope, String key, byte[] value, long flags, String session) implements KvCommand {
        private static final long serialVersionUID = 1L;

        public Unlock {
            Objects.requireNonNull(scope, "scope");
            KvState.requireKey(key);
            value = value == null ? new byte[0] : value.clone();
            if (session == null || session.isBlank()) throw new IllegalArgumentException("release names a session");
        }

        @Override
        public byte[] value() {
            return value.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Unlock unlock && scope.equals(unlock.scope) && key.equals(unlock.key)
                    && Arrays.equals(value, unlock.value) && flags == unlock.flags && session.equals(unlock.session);
        }

        @Override
        public int hashCode() {
            return Objects.hash(scope, key, Arrays.hashCode(value), flags, session);
        }

        @Override
        public String toString() {
            return "Unlock[" + scope + ":" + key + ", " + value.length + " bytes, session=" + session + "]";
        }
    }

    static KvCommand lock(KvScope scope, String key, byte[] value, long flags, String session, java.time.Instant at) {
        return new Lock(scope, key, value, flags, session, at);
    }

    static KvCommand unlock(KvScope scope, String key, byte[] value, long flags, String session) {
        return new Unlock(scope, key, value, flags, session);
    }

    static KvCommand set(KvScope scope, String key, byte[] value, long flags, Long cas) {
        return new Set(scope, key, value, flags, cas);
    }

    static KvCommand delete(KvScope scope, String key, Long cas) {
        return new Delete(scope, key, cas);
    }

    static KvCommand deleteTree(KvScope scope, String prefix) {
        return new DeleteTree(scope, prefix);
    }
}
