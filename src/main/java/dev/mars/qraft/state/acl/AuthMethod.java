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

package dev.mars.qraft.state.acl;

import java.time.Duration;
import java.util.Objects;

/**
 * An auth method (authentication list, Phase 1), as Consul's: its name, its type, {@code jwt} or, since the Kubernetes
 * auth method list, {@code kubernetes}, with the configuration of that type, the longest a login token it mints lives (decision 3), its configuration, and the Raft indexes that
 * created and last changed it.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public record AuthMethod(String name, String type, String description, Duration maxTokenTtl, AuthMethodConfig config,
                         long createIndex, long modifyIndex) {
    /** A login token's life when the method names none. */
    public static final Duration DEFAULT_MAX_TOKEN_TTL = Duration.ofHours(1);
    /** The longest a login token may live. */
    public static final Duration MAX_TOKEN_TTL = Duration.ofHours(24);

    public AuthMethod {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        description = description == null ? "" : description;
        maxTokenTtl = maxTokenTtl == null || maxTokenTtl.isZero() ? DEFAULT_MAX_TOKEN_TTL : maxTokenTtl;
        Objects.requireNonNull(config, "config");
    }

    AuthMethod at(long create, long modify) {
        return new AuthMethod(name, type, description, maxTokenTtl, config, create, modify);
    }
}
