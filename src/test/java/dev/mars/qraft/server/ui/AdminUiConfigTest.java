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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.server.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that {@code server.ui} is parsed into an {@link AdminUiConfig} and validated before the server opens
 * anything: its defaults, a path that may not overlap API, health, Raft, status, metrics, or debugging
 * routes, and a development asset directory that must exist, hold {@code index.html}, and only accompany an
 * enabled interface.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-27
 * @version 1.0
 */
class AdminUiConfigTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void theInterfaceIsEnabledAtUiFromTheEmbeddedAssetsByDefault() {
        AdminUiConfig ui = parse("{}").getAdminUi();

        assertTrue(ui.enabled());
        assertEquals("/ui/", ui.path());
        assertEquals(Optional.empty(), ui.devAssetsDirectory());
    }

    @Test
    void parsesAnExplicitPathADisabledInterfaceAndADevelopmentAssetDirectory() throws Exception {
        Path assets = Files.createDirectories(temporaryDirectory.resolve("dist"));
        Files.writeString(assets.resolve("index.html"), "<!doctype html>");

        AdminUiConfig console = parse("{\"path\":\"/console/\",\"devAssetsDirectory\":"
                + JSON.writeValueAsString(assets.toString()) + "}").getAdminUi();
        AdminUiConfig disabled = parse("{\"enabled\":false}").getAdminUi();

        assertEquals("/console/", console.path());
        assertEquals(Optional.of(assets.toAbsolutePath().normalize()), console.devAssetsDirectory());
        assertFalse(disabled.enabled());
    }

    @Test
    void rejectsAPathThatIsMalformedOrOverlapsAnotherRoute() {
        for (String path : List.of("", "/", "ui/", "/ui", "//ui/", "/ui//", "/ui/../x/", "/ui/./x/", "/u i/",
                "/ui?x/", "/v1/", "/api/", "/health/", "/raft/", "/status/", "/metrics/", "/debug/", "/v1/ui/")) {
            assertThrows(IllegalArgumentException.class, () -> parse("{\"path\":\"" + path + "\"}"), path);
        }
    }

    @Test
    void rejectsAPathWithUppercaseLettersBecauseRoutesAreCaseSensitive() {
        for (String path : List.of("/UI/", "/Ui/", "/ui/Admin/", "/API/", "/V1/")) {
            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                    () -> parse("{\"path\":\"" + path + "\"}"), path);
            assertTrue(rejected.getMessage().contains("segments must be lowercase"), rejected.getMessage());
        }
    }

    @Test
    void rejectsADevelopmentDirectoryThatIsMissingHasNoIndexOrAccompaniesADisabledInterface() throws Exception {
        Path missing = temporaryDirectory.resolve("missing");
        Path empty = Files.createDirectories(temporaryDirectory.resolve("empty"));
        Path file = Files.writeString(temporaryDirectory.resolve("file.html"), "x");
        Path complete = Files.createDirectories(temporaryDirectory.resolve("complete"));
        Files.writeString(complete.resolve("index.html"), "<!doctype html>");

        for (Path directory : List.of(missing, empty, file)) {
            assertThrows(IllegalArgumentException.class, () -> parse("{\"devAssetsDirectory\":"
                    + JSON.writeValueAsString(directory.toString()) + "}"), directory.toString());
        }
        assertThrows(IllegalArgumentException.class, () -> parse("{\"enabled\":false,\"devAssetsDirectory\":"
                + JSON.writeValueAsString(complete.toString()) + "}"),
                "a development directory for a disabled interface is contradictory");
    }

    @Test
    void rejectsUnknownSettingsAndWrongTypes() {
        for (String ui : List.of("{\"assets\":\"x\"}", "{\"enabled\":\"yes\"}", "{\"path\":5}",
                "{\"devAssetsDirectory\":\"\"}", "true")) {
            assertThrows(IllegalArgumentException.class, () -> parse(ui), ui);
        }
    }

    private static AppConfig parse(String ui) {
        return AppConfig.fromJson("{\"version\":1,\"server\":{\"ui\":" + ui + "}}");
    }
}
