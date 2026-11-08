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

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * The key/value calls (client library list, decision 3), Consul's {@code KV()}: values are bytes, a write answers
 * whether it was made, and a read of nothing answers empty. A key is sent as its path, each segment encoded.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public final class KeyValue {
    private final QraftApi api;

    KeyValue(QraftApi api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    /** The key, or empty when there is none. */
    public Optional<KvEntry> get(String key) {
        return get(key, QueryOptions.DEFAULT).value();
    }

    /** The key, or empty, with the index to wait past next. */
    public Indexed<Optional<KvEntry>> get(String key, QueryOptions options) {
        Indexed<List<KvEntry>> read = read(key, Map.of(), options);
        return new Indexed<>(read.value().stream().findFirst(), read.index());
    }

    /** Every key under {@code prefix}, in order; empty when there is none. */
    public List<KvEntry> list(String prefix) {
        return list(prefix, QueryOptions.DEFAULT).value();
    }

    public Indexed<List<KvEntry>> list(String prefix, QueryOptions options) {
        return read(prefix, flag("recurse"), options);
    }

    /** The names of the keys under {@code prefix}, cut after {@code separator} when it is given. */
    public List<String> keys(String prefix, String separator) {
        Map<String, String> query = flag("keys");
        if (separator != null) query.put("separator", separator);
        QraftApi.Response answer = api.send("GET", path(prefix), query, null, QueryOptions.DEFAULT);
        if (answer.status() == 404) return List.of();
        if (answer.status() != 200) throw QraftApi.refusal(answer);
        List<String> keys = new ArrayList<>();
        parse(answer).forEach(name -> keys.add(name.asText()));
        return List.copyOf(keys);
    }

    public boolean put(String key, byte[] value) {
        return write("PUT", key, Map.of(), value);
    }

    public boolean put(String key, byte[] value, long flags) {
        return write("PUT", key, Map.of("flags", Long.toUnsignedString(flags)), value);
    }

    /** Writes {@code value} only if the key's modify index is {@code modifyIndex}, or, given 0, if there is no key. */
    public boolean cas(String key, byte[] value, long modifyIndex) {
        return write("PUT", key, Map.of("cas", Long.toString(modifyIndex)), value);
    }

    /** Locks the key for {@code session}, writing {@code value}; false when another session holds it or its lock delay
     * has not passed. */
    public boolean acquire(String key, byte[] value, String session) {
        return write("PUT", key, Map.of("acquire", Objects.requireNonNull(session, "session")), value);
    }

    /** Unlocks the key that {@code session} holds, writing {@code value}; false when the session does not hold it. */
    public boolean release(String key, byte[] value, String session) {
        return write("PUT", key, Map.of("release", Objects.requireNonNull(session, "session")), value);
    }

    public boolean delete(String key) {
        return write("DELETE", key, Map.of(), null);
    }

    /** Deletes every key under {@code prefix}. */
    public boolean deleteTree(String prefix) {
        return write("DELETE", prefix, flag("recurse"), null);
    }

    private Indexed<List<KvEntry>> read(String key, Map<String, String> query, QueryOptions options) {
        QraftApi.Response answer = api.send("GET", path(key), query, null, Objects.requireNonNull(options, "options"));
        if (answer.status() == 404 && isNoKey(answer)) return new Indexed<>(List.of(), answer.index());
        if (answer.status() != 200) throw QraftApi.refusal(answer);
        List<KvEntry> entries = new ArrayList<>();
        for (JsonNode entry : parse(answer)) entries.add(entry(entry));
        return new Indexed<>(List.copyOf(entries), answer.index());
    }

    private boolean write(String method, String key, Map<String, String> query, byte[] value) {
        QraftApi.Response answer = api.send(method, path(key), query, value == null && method.equals("PUT")
                ? new byte[0] : value, null);
        if (answer.status() != 200) throw QraftApi.refusal(answer);
        return Boolean.parseBoolean(answer.text().trim());
    }

    private static boolean isNoKey(QraftApi.Response answer) {
        return QraftApi.refusal(answer).code().equals("key_not_found");
    }

    private static KvEntry entry(JsonNode entry) {
        String value = entry.path("Value").isNull() ? "" : entry.path("Value").asText("");
        return new KvEntry(entry.path("Key").asText(), Base64.getDecoder().decode(value),
                Long.parseUnsignedLong(entry.path("Flags").asText("0")), entry.path("CreateIndex").asLong(),
                entry.path("ModifyIndex").asLong(), entry.path("LockIndex").asLong(),
                entry.hasNonNull("Session") ? entry.get("Session").asText() : null);
    }

    private static JsonNode parse(QraftApi.Response answer) {
        try {
            return QraftApi.JSON.readTree(answer.body());
        } catch (IOException notJson) {
            throw new QraftApiException(answer.status(), "invalid_answer", "The answer is not JSON: " + answer.text());
        }
    }

    private static Map<String, String> flag(String name) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put(name, null);
        return query;
    }

    private static String path(String key) {
        Objects.requireNonNull(key, "key");
        StringJoiner path = new StringJoiner("/", "/v1/kv/", "");
        // A trailing slash is kept: it is part of a prefix.
        for (String segment : key.split("/", -1)) path.add(QraftApi.encode(segment));
        return path.toString();
    }
}
