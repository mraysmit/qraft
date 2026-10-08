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

package dev.mars.qraft.server.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Reads interface assets from a development directory, the explicit {@code server.ui.devAssetsDirectory}
 * override for rapid frontend iteration. A path that would resolve outside the directory reads as absent.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-27
 * @version 1.0
 */
public final class DirectoryUiAssets implements UiAssets {
    private final Path root;

    public DirectoryUiAssets(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public Optional<byte[]> read(String relativePath) throws IOException {
        Path file = root.resolve(relativePath).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) return Optional.empty();
        return Optional.of(Files.readAllBytes(file));
    }
}
