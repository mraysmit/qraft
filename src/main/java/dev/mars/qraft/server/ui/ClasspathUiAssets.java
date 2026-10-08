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
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads interface assets as classpath resources under a root. Inside the shaded runtime JAR these are ZIP
 * entries, so they are read only through resource streams, never converted to filesystem paths.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-27
 * @version 1.0
 */
public final class ClasspathUiAssets implements UiAssets {
    private final ClassLoader loader;
    private final String root;

    /** Resources under {@code root}, which ends with {@code /}, from {@code loader}. */
    public ClasspathUiAssets(ClassLoader loader, String root) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.root = Objects.requireNonNull(root, "root");
        if (!root.endsWith("/")) throw new IllegalArgumentException("root must end with /: " + root);
    }

    @Override
    public Optional<byte[]> read(String relativePath) throws IOException {
        try (InputStream resource = loader.getResourceAsStream(root + relativePath)) {
            return resource == null ? Optional.empty() : Optional.of(resource.readAllBytes());
        }
    }
}
