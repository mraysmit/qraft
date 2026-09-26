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

package dev.mars.qraft.tenant.model;

import java.time.Instant;
import java.util.Map;

/**
 * A Consul-style namespace for isolating service registrations and policies.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-09
 * @version 1.0
 */
public record Namespace(
        String name,
        String description,
        Map<String, String> metadata,
        Instant createdAt,
        Instant updatedAt) {

    public Namespace {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Namespace name cannot be blank");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    public static Namespace named(String name) {
        return new Namespace(name, null, Map.of(), Instant.now(), null);
    }

    public Namespace withDescription(String value) {
        return new Namespace(name, value, metadata, createdAt, Instant.now());
    }

    public Namespace withMetadata(Map<String, String> value) {
        return new Namespace(name, description, value, createdAt, Instant.now());
    }
}
