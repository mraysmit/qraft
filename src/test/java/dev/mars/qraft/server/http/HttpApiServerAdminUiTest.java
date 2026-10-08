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

package dev.mars.qraft.server.http;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.mars.qraft.server.ui.AdminUiConfig;
import dev.mars.qraft.server.ui.ClasspathUiAssets;
import dev.mars.qraft.server.ui.DirectoryUiAssets;
import dev.mars.qraft.server.ui.UiAssets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the administrative interface's route contract on the server's HTTP listener: it is registered at
 * its configured path only when enabled, serves named files from classpath resources or a development
 * directory, refuses to leave its asset root, answers only {@code GET} and {@code HEAD}, leaves every other
 * route untouched, and warns once at startup that it is unauthenticated.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-27
 * @version 1.0
 */
class HttpApiServerAdminUiTest {
    private static final UiAssets FIXTURE = new ClasspathUiAssets(
            HttpApiServerAdminUiTest.class.getClassLoader(), "ui-fixture/");

    @TempDir
    Path temporaryDirectory;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private HttpApiServer server;

    @AfterEach
    void stop() {
        if (server != null) server.close();
        http.close();
    }

    @Test
    void anEnabledInterfaceServesItsIndexAndNamedAssetsFromTheClasspath() throws Exception {
        start(new AdminUiConfig(true, "/ui/", Optional.empty()), FIXTURE);

        HttpResponse<String> index = get("/ui/");
        HttpResponse<String> script = get("/ui/assets/app-3f2a1b.js");
        HttpResponse<String> style = get("/ui/assets/app-3f2a1b.css");

        assertEquals(200, index.statusCode());
        assertTrue(index.body().contains("Qraft fixture"));
        assertEquals("text/html; charset=utf-8", index.headers().firstValue("Content-Type").orElse(""));
        assertEquals(200, script.statusCode());
        assertEquals("text/javascript; charset=utf-8", script.headers().firstValue("Content-Type").orElse(""));
        assertEquals("text/css; charset=utf-8", style.headers().firstValue("Content-Type").orElse(""));
    }

    @Test
    void aMissingNamedAssetIsNotFound() throws Exception {
        start(new AdminUiConfig(true, "/ui/", Optional.empty()), FIXTURE);

        assertEquals(404, get("/ui/assets/missing-000000.js").statusCode());
        assertEquals(404, get("/ui/assets").statusCode(), "a directory is never served");
    }

    @Test
    void aRequestCannotLeaveTheAssetRoot() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("dist"));
        Files.writeString(root.resolve("index.html"), "<!doctype html><title>dev</title>");
        Files.writeString(temporaryDirectory.resolve("secret.txt"), "secret");
        start(new AdminUiConfig(true, "/ui/", Optional.of(root)), new DirectoryUiAssets(root));

        for (String path : List.of("/ui/../secret.txt", "/ui/%2e%2e/secret.txt", "/ui/..%2fsecret.txt",
                "/ui/assets/..%5c..%5csecret.txt")) {
            HttpResponse<String> response = get(path);
            assertTrue(response.statusCode() == 404 || response.statusCode() == 400, path + " -> " + response.statusCode());
            assertTrue(!response.body().contains("secret"), path);
        }
        assertEquals(200, get("/ui/").statusCode(), "the development directory serves its own index");
    }

    @Test
    void onlyGetAndHeadAreAnswered() throws Exception {
        start(new AdminUiConfig(true, "/ui/", Optional.empty()), FIXTURE);

        HttpResponse<Void> head = http.send(HttpRequest.newBuilder(uri("/ui/")).timeout(Duration.ofSeconds(5))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
        HttpResponse<String> post = http.send(HttpRequest.newBuilder(uri("/ui/")).timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString("x")).build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, head.statusCode());
        assertEquals(405, post.statusCode());
    }

    @Test
    void aDisabledInterfaceRegistersNoRouteAndOtherRoutesAreUntouched() throws Exception {
        start(new AdminUiConfig(false, "/ui/", Optional.empty()), FIXTURE);

        assertEquals(404, get("/ui/").statusCode());
        assertEquals(200, get("/health/live").statusCode());
    }

    @Test
    void anEnabledInterfaceLeavesTheApiAndHealthRoutesAlone() throws Exception {
        start(new AdminUiConfig(true, "/console/", Optional.empty()), FIXTURE);

        assertEquals(200, get("/console/").statusCode());
        assertEquals(404, get("/ui/").statusCode());
        assertEquals(200, get("/health/live").statusCode());
    }

    @Test
    void anEnabledInterfaceWarnsOnceAtStartupThatItIsUnauthenticated() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(HttpApiServer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            start(new AdminUiConfig(true, "/ui/", Optional.empty()), FIXTURE);
        } finally {
            logger.detachAppender(appender);
        }

        assertEquals(1, appender.list.stream().filter(event ->
                event.getFormattedMessage().contains("/ui/")
                        && event.getFormattedMessage().contains("unauthenticated")).count());
    }

    private void start(AdminUiConfig ui, UiAssets assets) throws Exception {
        server = new HttpApiServer(0, null, null, Clock.systemUTC(), ui, assets);
        server.start().get(10, TimeUnit.SECONDS);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }
}
