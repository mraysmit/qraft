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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests what the build embeds for the administrative interface, read through the classloader as the server reads it
 * (task list, Step 3; design section 12.4.1): {@code index.html} and Vite's {@code asset-manifest.json} below
 * {@code META-INF/qraft/ui/}, every file the manifest lists below {@code assets/}, and no source map. The index links
 * its entry relative to the page, which the server's {@code <base>} resolves below whatever path is configured, and
 * holds no inline script, which the content security policy would refuse.
 *
 * <p>The resources come from the frontend build in {@code generate-resources}, so this test needs a Maven build of the
 * project, not only its compiled classes.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-13
 * @version 1.0
 */
class EmbeddedUiResourcesTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern INLINE_SCRIPT = Pattern.compile("<script(?![^>]*\\bsrc=)[^>]*>",
            Pattern.CASE_INSENSITIVE);

    private final UiAssets embedded = new ClasspathUiAssets(EmbeddedUiResourcesTest.class.getClassLoader(),
            UiAssets.EMBEDDED_ROOT);

    @Test
    void theBuildEmbedsTheIndexAndTheAssetManifest() throws IOException {
        assertTrue(embedded.read("index.html").isPresent(), "index.html is not embedded below " + UiAssets.EMBEDDED_ROOT);
        assertTrue(embedded.read("asset-manifest.json").isPresent(), "the asset manifest is not embedded");
    }

    @Test
    void everyFileTheManifestListsIsEmbeddedBelowAssetsWithoutASourceMap() throws IOException {
        Set<String> files = listedFiles(manifest());

        assertFalse(files.isEmpty(), "the manifest lists no file");
        for (String file : files) {
            assertTrue(file.startsWith("assets/"), "a listed file outside assets/: " + file);
            byte[] body = embedded.read(file).orElseThrow(() -> new AssertionError("listed but not embedded: " + file));
            assertTrue(embedded.read(file + ".map").isEmpty(), "a source map is embedded for " + file);
            assertFalse(new String(body, StandardCharsets.UTF_8).contains("sourceMappingURL"),
                    "a source map is referenced from " + file);
        }
    }

    @Test
    void everyFileBelowAssetsIsOneTheManifestLists() throws Exception {
        // On 2026-11-14 a `mvn package` without `clean` packaged the previous build's bundle beside the new one: Vite
        // empties its own output, but the resources copied into target/classes stayed. Under `mvn test` the
        // resources are that directory, so it can be listed here; server code reads them only as resources.
        java.net.URL assets = getClass().getClassLoader().getResource(UiAssets.EMBEDDED_ROOT + "assets/");
        assertTrue(assets != null && "file".equals(assets.getProtocol()), "the assets are not a directory: " + assets);
        Set<String> present = new TreeSet<>();
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(java.nio.file.Path.of(assets.toURI()))) {
            files.forEach(file -> present.add("assets/" + file.getFileName()));
        }

        assertEquals(listedFiles(manifest()), present, "a file below assets/ that this build's manifest does not list");
    }

    @Test
    void noScriptPassesTheSizeAtWhichViteWarns() throws IOException {
        // UI-3 task list, decision 4: the bundle was one script of 1,014 kB, above Vite's 500 kB warning, until the
        // application and its libraries were split into chunks.
        for (String file : listedFiles(manifest())) {
            if (!file.endsWith(".js")) continue;
            int size = embedded.read(file).orElseThrow().length;
            assertTrue(size <= 500 * 1024, file + " is " + size + " bytes");
        }
    }

    @Test
    void theIndexLinksItsEntryRelativeToThePageAndHoldsNoInlineScript() throws IOException {
        String index = new String(embedded.read("index.html").orElseThrow(), StandardCharsets.UTF_8);
        String entry = entryFile(manifest());

        assertTrue(index.contains("\"./" + entry + "\""), "the entry " + entry + " is not linked as ./" + entry
                + " in " + index);
        assertFalse(index.contains("\"/assets/"), "an asset linked from the origin's root: " + index);
        assertFalse(INLINE_SCRIPT.matcher(index).find(), "an inline script: " + index);
    }

    private JsonNode manifest() throws IOException {
        return JSON.readTree(embedded.read("asset-manifest.json")
                .orElseThrow(() -> new AssertionError("the asset manifest is not embedded")));
    }

    private static Set<String> listedFiles(JsonNode manifest) {
        Set<String> files = new TreeSet<>();
        for (JsonNode chunk : manifest) {
            if (chunk.hasNonNull("file")) files.add(chunk.get("file").asText());
            chunk.path("css").forEach(file -> files.add(file.asText()));
            chunk.path("assets").forEach(file -> files.add(file.asText()));
        }
        return files;
    }

    private static String entryFile(JsonNode manifest) {
        for (JsonNode chunk : manifest) {
            if (chunk.path("isEntry").asBoolean()) return chunk.path("file").asText();
        }
        throw new AssertionError("the manifest names no entry: " + manifest);
    }
}
