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

/**
 * Reads a versioned recovery input file into a {@link RecoveryPlan}. The file names the stopped source, the server
 * ID it must hold, and the server JSON the seed will start with; that JSON supplies the output directory
 * ({@code server.raft.storage.path}), the name ({@code server.id}) and the Raft address
 * ({@code server.raft.nodes[server.id]}). Both files are read with the server JSON rules: {@code version} 1, and
 * unknown or duplicate keys, placeholders and missing values refused.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-22
 * @version 1.0
 */
public final class RecoveryInput {
    private RecoveryInput() { }

    /** Reads {@code inputFile}; an invalid input or server JSON is refused with {@link IllegalArgumentException}. */
    public static RecoveryPlan read(Path inputFile) {
        throw new UnsupportedOperationException("Recovery input parsing is not implemented yet");
    }
}
