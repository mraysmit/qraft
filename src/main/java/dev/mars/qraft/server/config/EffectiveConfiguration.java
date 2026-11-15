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

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A server's effective configuration, its secrets withheld (UI-4 list, decision 3): the file it was read from, if it
 * was read from one, and every setting in the document's order.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-15
 * @version 1.0
 */
public record EffectiveConfiguration(Optional<Path> file, List<EffectiveSetting> settings) {
    public EffectiveConfiguration {
        Objects.requireNonNull(file, "file");
        settings = List.copyOf(Objects.requireNonNull(settings, "settings"));
    }
}
