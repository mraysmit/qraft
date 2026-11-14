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

package dev.mars.qraft.raft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.ComposeContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each server container of the {@code qraft-runtime:test} image serves the administrative interface from the jar it
 * was built from: the rendered index, the asset manifest, and the bundle the manifest names, with the API the page
 * calls answered below the base path its bootstrap object names (the interface's task list, Step 5).
 *
 * <p>Requires Docker. Excluded from the default build; run with
 * {@code mvn test -Dgroups=docker -Dtest.excludedGroups=}.</p>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-14
 * @version 1.0
 */
@Tag("docker")
@Execution(ExecutionMode.CONCURRENT)
class DockerAdminUiTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern BOOTSTRAP = Pattern.compile(
            "<script id=\"qraft-bootstrap\" type=\"application/json\" nonce=\"[^\"]+\">(.*?)</script>");
    private static final ComposeContainer environment = SharedDockerClusterFixture.getThreeNodeCluster();
    private static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Test
    void everyServerContainerServesTheInterfaceAndTheApiItsPageCalls() throws Exception {
        for (String endpoint : SharedDockerClusterFixture.getNodeEndpoints(environment, 3)) {
            URI origin = URI.create(endpoint);

            HttpResponse<String> index = get(origin, "/ui/");
            assertEquals(200, index.statusCode(), endpoint);
            assertTrue(index.headers().firstValue("Content-Security-Policy").orElse("").contains("default-src 'none'"),
                    endpoint);
            Matcher bootstrap = BOOTSTRAP.matcher(index.body());
            assertTrue(bootstrap.find(), endpoint + " renders no bootstrap object: " + index.body());
            String apiBase = JSON.readTree(bootstrap.group(1)).path("apiBasePath").asText();

            HttpResponse<String> manifest = get(origin, "/ui/asset-manifest.json");
            assertEquals(200, manifest.statusCode(), endpoint);
            String entry = entryFile(JSON.readTree(manifest.body()));
            assertTrue(index.body().contains("\"./" + entry + "\""), endpoint + " links " + entry);
            HttpResponse<String> bundle = get(origin, "/ui/" + entry);
            assertEquals(200, bundle.statusCode(), endpoint + " " + entry);
            assertEquals("text/javascript; charset=utf-8", bundle.headers().firstValue("Content-Type").orElse(""));
            assertEquals("public, max-age=31536000, immutable", bundle.headers().firstValue("Cache-Control").orElse(""));

            assertEquals(200, get(origin.resolve(apiBase), "raft/status").statusCode(), endpoint + " " + apiBase);
            assertEquals(200, get(origin.resolve(apiBase), "v1/catalog/services").statusCode(), endpoint + " " + apiBase);
        }
    }

    private static HttpResponse<String> get(URI base, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String entryFile(JsonNode manifest) {
        for (JsonNode chunk : manifest) {
            if (chunk.path("isEntry").asBoolean()) return chunk.path("file").asText();
        }
        throw new AssertionError("the manifest names no entry: " + manifest);
    }
}
