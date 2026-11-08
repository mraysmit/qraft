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

import java.util.Arrays;
import java.util.Objects;

/**
 * One key/value entry (key/value list, Phase 1; design section 10), as Consul's KV store keeps it.
 *
 * @param tenant      the entry's tenant
 * @param namespace   the entry's namespace
 * @param key         the key, never empty and never starting with {@code /}
 * @param value       the opaque value, possibly empty; never shared with a caller
 * @param flags       a caller's 64-bit number, kept as given
 * @param createIndex the Raft index at which the key was created
 * @param modifyIndex the Raft index at which it was last written
 * @param lockIndex   how many times a session has acquired it (sessions list); 0 before any
 * @param session     the session that holds it, or {@code null} when none does
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public record KvEntry(String tenant, String namespace, String key, byte[] value, long flags, long createIndex,
                      long modifyIndex, long lockIndex, String session) {
    /** An entry no session has held. */
    public KvEntry(String tenant, String namespace, String key, byte[] value, long flags, long createIndex,
                   long modifyIndex) {
        this(tenant, namespace, key, value, flags, createIndex, modifyIndex, 0, null);
    }

    public KvEntry {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(namespace, "namespace");
        KvState.requireKey(key);
        value = value == null ? new byte[0] : value.clone();
    }

    /** A copy of the value, so no caller can change the replicated one. */
    @Override
    public byte[] value() {
        return value.clone();
    }

    /** The entry's scope. */
    public KvScope scope() {
        return new KvScope(tenant, namespace);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof KvEntry entry && tenant.equals(entry.tenant) && namespace.equals(entry.namespace)
                && key.equals(entry.key) && Arrays.equals(value, entry.value) && flags == entry.flags
                && createIndex == entry.createIndex && modifyIndex == entry.modifyIndex
                && lockIndex == entry.lockIndex && Objects.equals(session, entry.session);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenant, namespace, key, Arrays.hashCode(value), flags, createIndex, modifyIndex,
                lockIndex, session);
    }

    @Override
    public String toString() {
        return "KvEntry[" + tenant + "/" + namespace + ":" + key + ", " + value.length + " bytes, flags=" + flags
                + ", createIndex=" + createIndex + ", modifyIndex=" + modifyIndex + ", lockIndex=" + lockIndex
                + ", session=" + session + "]";
    }
}
