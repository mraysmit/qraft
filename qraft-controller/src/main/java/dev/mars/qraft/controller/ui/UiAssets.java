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

package dev.mars.qraft.controller.ui;

import java.io.IOException;
import java.util.Optional;

/**
 * The administrative interface's static files, read by relative path such as {@code index.html} or
 * {@code assets/app-1a2b3c.js}. Callers pass only paths already checked to stay inside the asset root.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-27
 * @version 1.0
 */
public interface UiAssets {
    /** Classpath root of the assets embedded in the runtime JAR. */
    String EMBEDDED_ROOT = "META-INF/qraft/ui/";

    /** The file's bytes, or empty when there is no such file. */
    Optional<byte[]> read(String relativePath) throws IOException;

    /** The development directory when configured, and otherwise the embedded classpath resources. */
    static UiAssets forConfig(AdminUiConfig config) {
        return config.devAssetsDirectory().<UiAssets>map(DirectoryUiAssets::new)
                .orElseGet(() -> new ClasspathUiAssets(UiAssets.class.getClassLoader(), EMBEDDED_ROOT));
    }
}
