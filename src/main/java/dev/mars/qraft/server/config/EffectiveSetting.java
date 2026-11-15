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

package dev.mars.qraft.server.config;

import java.util.Objects;

/**
 * One setting of a server's effective configuration (UI-4 list, decision 3): its {@code key} in the configuration
 * document, such as {@code server.raft.electionTimeoutMs}; the {@code value} in force, a string, number, boolean or
 * map, or {@code null} when it has none; whether the document gave it ({@code configured}) or the default did; and
 * whether its value is withheld as a secret ({@code redacted}), when {@code value} is what may be shown of it, if
 * anything.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-15
 * @version 1.0
 */
public record EffectiveSetting(String key, Object value, boolean configured, boolean redacted) {
    public EffectiveSetting {
        Objects.requireNonNull(key, "key");
    }
}
