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

package dev.mars.qraft.common;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Client-owned declaration of a service that an agent should publish.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-24
 * @version 1.0
 */
public record ServiceDefinition(String id, String name, String address, int port,
                                List<String> tags, Map<String, String> metadata, boolean enabled,
                                List<String> checkIds) {
    public ServiceDefinition {
        id = required("service id", id);
        name = required("service name", name);
        address = required("service address", address);
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("service port must be between 1 and 65535");
        }
        tags = List.copyOf(tags == null ? List.of() : tags);
        metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
        if (tags.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("service tags must not contain blank values");
        }
        if (metadata.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getKey().isBlank() || entry.getValue() == null)) {
            throw new IllegalArgumentException("service metadata keys must be non-blank and values non-null");
        }
        checkIds = canonicalCheckIds(checkIds == null ? List.of() : checkIds);
    }

    /** Definition that declares no health checks. */
    public ServiceDefinition(String id, String name, String address, int port,
                             List<String> tags, Map<String, String> metadata, boolean enabled) {
        this(id, name, address, port, tags, metadata, enabled, List.of());
    }

    /** Returns this definition declaring exactly the given check identifiers. */
    public ServiceDefinition withCheckIds(Collection<String> declaredCheckIds) {
        return new ServiceDefinition(id, name, address, port, tags, metadata, enabled, List.copyOf(declaredCheckIds));
    }

    private static List<String> canonicalCheckIds(Collection<String> values) {
        TreeSet<String> canonical = new TreeSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("service check identifiers must not be blank");
            }
            canonical.add(value);
        }
        return List.copyOf(canonical);
    }

    private static String required(String name, String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}
