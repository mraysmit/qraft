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

package dev.mars.qraft.server.recovery;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One lost-quorum recovery, as {@link RecoveryInput} resolves it (membership Step 7, decision 8): the stopped
 * {@code source} directory and the server ID it must hold, and the {@code output} directory, name and Raft
 * address of the seed, all three taken from the server JSON the seed will start with.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-22
 * @version 1.0
 */
public record RecoveryPlan(Path source, String sourceServerId, Path output, String name, String address) {
    public RecoveryPlan {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sourceServerId, "sourceServerId");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(address, "address");
    }
}
