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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validated {@code server.ui} settings for the embedded administrative interface.
 *
 * <p>The path is a lowercase, slash-terminated prefix such as {@code /ui/} whose first segment is not one of
 * the server's own routes, so the interface can never shadow an API, health, Raft, status, metrics, or
 * debugging route. A development asset directory, when given, replaces the embedded assets for rapid
 * frontend iteration; it must exist, hold {@code index.html}, and accompany an enabled interface. Production
 * serves the embedded assets only.
 *
 * @param enabled            whether the server registers the interface's route
 * @param path               the route prefix, beginning and ending with {@code /}
 * @param devAssetsDirectory a development-only asset directory, normalized to an absolute path
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-27
 * @version 1.0
 */
public record AdminUiConfig(boolean enabled, String path, Optional<Path> devAssetsDirectory) {
    public static final String DEFAULT_PATH = "/ui/";

    private static final Set<String> RESERVED_SEGMENTS =
            Set.of("v1", "api", "health", "raft", "status", "metrics", "debug");
    private static final Pattern SEGMENT = Pattern.compile("[a-z0-9][a-z0-9-]*");

    public AdminUiConfig {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(devAssetsDirectory, "devAssetsDirectory");
        if (path.length() < 3 || !path.startsWith("/") || !path.endsWith("/")) {
            throw new IllegalArgumentException("server.ui.path must be a prefix such as /ui/, beginning and "
                    + "ending with /: " + path);
        }
        String[] segments = path.substring(1, path.length() - 1).split("/", -1);
        for (String segment : segments) {
            if (!SEGMENT.matcher(segment).matches()) {
                throw new IllegalArgumentException("server.ui.path segments must be lowercase letters, digits, "
                        + "and hyphens: " + path);
            }
        }
        if (RESERVED_SEGMENTS.contains(segments[0])) {
            throw new IllegalArgumentException("server.ui.path must not overlap the server's /" + segments[0]
                    + " routes: " + path);
        }
        devAssetsDirectory = devAssetsDirectory.map(directory -> directory.toAbsolutePath().normalize());
        devAssetsDirectory.ifPresent(directory -> {
            if (!enabled) {
                throw new IllegalArgumentException("server.ui.devAssetsDirectory requires an enabled interface");
            }
            if (!Files.isDirectory(directory)) {
                throw new IllegalArgumentException("server.ui.devAssetsDirectory is not a directory: " + directory);
            }
            if (!Files.isRegularFile(directory.resolve("index.html"))) {
                throw new IllegalArgumentException("server.ui.devAssetsDirectory has no index.html: " + directory);
            }
        });
    }

    /** The interface as a server without {@code server.ui} settings runs it: enabled at {@code /ui/}. */
    public static AdminUiConfig defaults() {
        return new AdminUiConfig(true, DEFAULT_PATH, Optional.empty());
    }

    /** No interface route at all. */
    public static AdminUiConfig disabled() {
        return new AdminUiConfig(false, DEFAULT_PATH, Optional.empty());
    }
}
