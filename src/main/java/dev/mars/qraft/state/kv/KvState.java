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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The replicated key/value state (key/value list, Phase 1; design section 10), on Consul's KV store: entries by
 * tenant, namespace and key, written at the Raft index of the command that writes them. A set or a delete with a
 * compare-and-set index applies only when that index is the entry's {@code modifyIndex}, or, for 0, when the key is
 * absent. {@link #lastIndex} is the index of the last change, which a refused write or the delete of an absent key
 * does not make.
 *
 * <p>Commands are applied on the state machine's thread and read on others; a lock makes each command, a tree delete
 * among them, visible whole.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public final class KvState {
    private static final Comparator<Key> ORDER = Comparator.comparing(Key::tenant).thenComparing(Key::namespace)
            .thenComparing(Key::key);

    private record Key(String tenant, String namespace, String key) {
        static Key of(KvScope scope, String key) {
            return new Key(scope.tenant(), scope.namespace(), key);
        }
    }

    private final TreeMap<Key, KvEntry> entries = new TreeMap<>(ORDER);
    /** Until when each recently freed key cannot be acquired, by the leader's time (sessions list, decision 3). */
    private final TreeMap<Key, java.time.Instant> lockDelays = new TreeMap<>(ORDER);
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private long lastIndex;

    /** Refuses an empty key, or one that starts with {@code /}, which Consul's HTTP API strips. */
    static void requireKey(String key) {
        if (key == null || key.isEmpty()) throw new IllegalArgumentException("a key must not be empty");
        if (key.startsWith("/")) throw new IllegalArgumentException("a key must not start with /: " + key);
    }

    /** Writes {@code key} at {@code index} unless {@code cas} refuses it; whether it was written. */
    public boolean set(KvScope scope, String key, byte[] value, long flags, OptionalLong cas, long index) {
        requireKey(key);
        lock.writeLock().lock();
        try {
            Key at = Key.of(scope, key);
            KvEntry current = entries.get(at);
            if (!casHolds(current, cas)) return false;
            long created = current == null ? index : current.createIndex();
            // A plain write keeps a held key's lock, as Consul's kvsSetTxn keeps the session and lock index.
            entries.put(at, new KvEntry(scope.tenant(), scope.namespace(), key, value, flags, created, index,
                    current == null ? 0 : current.lockIndex(), current == null ? null : current.session()));
            lastIndex = index;
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Acquires {@code key} for {@code session} at {@code index}, as Consul's {@code kvsLockTxn} (sessions list, Phase 1):
     * refused while another session holds it, or while its lock delay runs past {@code now}; a new holder increments
     * its {@code LockIndex}, and its holder may write it again without. The caller has checked that the session is valid.
     */
    public boolean lock(KvScope scope, String key, byte[] value, long flags, String session, long index,
                        java.time.Instant now) {
        requireKey(key);
        lock.writeLock().lock();
        try {
            Key at = Key.of(scope, key);
            KvEntry current = entries.get(at);
            if (current != null && current.session() != null && !current.session().equals(session)) return false;
            java.time.Instant delayed = lockDelays.get(at);
            if (delayed != null && now.isBefore(delayed)) return false;
            lockDelays.remove(at);
            boolean newHolder = current == null || !session.equals(current.session());
            long lockIndex = (current == null ? 0 : current.lockIndex()) + (newHolder ? 1 : 0);
            entries.put(at, new KvEntry(scope.tenant(), scope.namespace(), key, value, flags,
                    current == null ? index : current.createIndex(), index, lockIndex, session));
            lastIndex = index;
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Releases {@code key} at {@code index} if {@code session} holds it, writing its value, as {@code kvsUnlockTxn}. */
    public boolean unlock(KvScope scope, String key, byte[] value, long flags, String session, long index) {
        requireKey(key);
        lock.writeLock().lock();
        try {
            Key at = Key.of(scope, key);
            KvEntry current = entries.get(at);
            if (current == null || !session.equals(current.session())) return false;
            entries.put(at, new KvEntry(scope.tenant(), scope.namespace(), key, value, flags, current.createIndex(),
                    index, current.lockIndex(), null));
            lastIndex = index;
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Frees every key {@code session} holds, in every scope, at {@code index}: released, keeping its value, or deleted,
     * as the session's behaviour has it; each one's lock delay runs to {@code delayedUntil} when that is set.
     */
    public void releaseSession(String session, boolean delete, long index, java.time.Instant delayedUntil) {
        lock.writeLock().lock();
        try {
            List<Map.Entry<Key, KvEntry>> held = entries.entrySet().stream()
                    .filter(entry -> session.equals(entry.getValue().session())).toList();
            for (Map.Entry<Key, KvEntry> entry : held) {
                KvEntry current = entry.getValue();
                if (delete) {
                    entries.remove(entry.getKey());
                } else {
                    entries.put(entry.getKey(), new KvEntry(current.tenant(), current.namespace(), current.key(),
                            current.value(), current.flags(), current.createIndex(), index, current.lockIndex(), null));
                }
                if (delayedUntil != null) lockDelays.put(entry.getKey(), delayedUntil);
            }
            if (!held.isEmpty()) lastIndex = index;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Deletes {@code key} unless {@code cas} refuses it; an absent key is deleted already, as in Consul. */
    public boolean delete(KvScope scope, String key, OptionalLong cas, long index) {
        requireKey(key);
        lock.writeLock().lock();
        try {
            Key at = Key.of(scope, key);
            KvEntry current = entries.get(at);
            if (current == null) return true;
            if (!casHolds(current, cas)) return false;
            entries.remove(at);
            lastIndex = index;
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Deletes every key of {@code scope} that starts with {@code prefix}; the empty prefix deletes them all. */
    public void deleteTree(KvScope scope, String prefix, long index) {
        lock.writeLock().lock();
        try {
            List<Key> doomed = under(scope, prefix).stream()
                    .map(entry -> Key.of(scope, entry.key())).toList();
            doomed.forEach(entries::remove);
            if (!doomed.isEmpty()) lastIndex = index;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Optional<KvEntry> get(KvScope scope, String key) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(entries.get(Key.of(scope, key)));
        } finally {
            lock.readLock().unlock();
        }
    }

    /** The entries of {@code scope} under {@code prefix}, in key order. */
    public List<KvEntry> list(KvScope scope, String prefix) {
        lock.readLock().lock();
        try {
            return under(scope, prefix);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * The keys of {@code scope} under {@code prefix}, in key order; with a separator, each stops at the first one
     * after the prefix and is given once, as Consul's {@code ?keys&separator} answers.
     */
    public List<String> keys(KvScope scope, String prefix, String separator) {
        return keysOf(list(scope, prefix), prefix, separator);
    }

    /** The keys of {@code entries}, which lie under {@code prefix}, cut after {@code separator} as {@link #keys}. */
    public static List<String> keysOf(List<KvEntry> entries, String prefix, String separator) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (KvEntry entry : entries) {
            String key = entry.key();
            int cut = separator == null || separator.isEmpty() ? -1 : key.indexOf(separator, prefix.length());
            keys.add(cut < 0 ? key : key.substring(0, cut + separator.length()));
        }
        return List.copyOf(keys);
    }

    /** The index of the last change to the key/value state; 0 before any. */
    public long lastIndex() {
        lock.readLock().lock();
        try {
            return lastIndex;
        } finally {
            lock.readLock().unlock();
        }
    }

    public Snapshot snapshot() {
        lock.readLock().lock();
        try {
            List<LockDelay> delays = lockDelays.entrySet().stream().map(entry -> new LockDelay(entry.getKey().tenant(),
                    entry.getKey().namespace(), entry.getKey().key(), entry.getValue())).toList();
            return new Snapshot(List.copyOf(entries.values()), lastIndex, delays);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Replaces the state with {@code snapshot}'s; none, from a snapshot written before key/value, empties it. */
    public void restore(Snapshot snapshot) {
        lock.writeLock().lock();
        try {
            entries.clear();
            lockDelays.clear();
            lastIndex = 0;
            if (snapshot == null) return;
            for (KvEntry entry : snapshot.entries()) {
                entries.put(new Key(entry.tenant(), entry.namespace(), entry.key()), entry);
            }
            for (LockDelay delay : snapshot.lockDelays()) {
                lockDelays.put(new Key(delay.tenant(), delay.namespace(), delay.key()), delay.until());
            }
            lastIndex = snapshot.lastIndex();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static boolean casHolds(KvEntry current, OptionalLong cas) {
        if (cas.isEmpty()) return true;
        long expected = cas.getAsLong();
        return expected == 0 ? current == null : current != null && current.modifyIndex() == expected;
    }

    /** Called with the lock held. */
    private List<KvEntry> under(KvScope scope, String prefix) {
        List<KvEntry> found = new ArrayList<>();
        for (Map.Entry<Key, KvEntry> entry : entries.tailMap(Key.of(scope, prefix), true).entrySet()) {
            Key key = entry.getKey();
            if (!key.tenant().equals(scope.tenant()) || !key.namespace().equals(scope.namespace())
                    || !key.key().startsWith(prefix)) {
                break;
            }
            found.add(entry.getValue());
        }
        return found;
    }

    /** The entries, the last index and the lock delays, for the state store's snapshot. */
    public record Snapshot(List<KvEntry> entries, long lastIndex, List<LockDelay> lockDelays) {
        public Snapshot {
            entries = entries == null ? List.of() : List.copyOf(entries);
            lockDelays = lockDelays == null ? List.of() : List.copyOf(lockDelays);
        }
    }

    /** One key's lock delay in a snapshot; absent from snapshots written before sessions. */
    public record LockDelay(String tenant, String namespace, String key, java.time.Instant until) { }
}
