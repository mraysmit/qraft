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

package dev.mars.qraft.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.raft.SharedDockerClusterFixture;
import dev.mars.qraft.testing.fault.SubprocessOutputAuditHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shaded runtime JAR, started with {@code java -jar} from a directory that holds only its configuration, serves the
 * administrative interface from the resources packaged in it; a server with the interface disabled, and a client, serve
 * none of its routes (design section 19.6; the interface's task list, Step 5). Run after {@code mvn install}, as the
 * end-to-end stage is, against the {@code target/qraft.jar} that build packaged.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-14
 * @version 1.0
 */
@Tag("e2e")
class PackagedAdminUiEndToEndTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path REPOSITORY = Path.of("").toAbsolutePath();
    private static final Path JAR = REPOSITORY.resolve("target").resolve("qraft.jar");
    private static final String UI_ROOT = "META-INF/qraft/ui/";

    @TempDir
    Path directory;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private Process server;
    private Process client;

    @BeforeEach
    void theJarIsTheOneThisBuildPackaged() {
        SharedDockerClusterFixture.assertRuntimeJarIsCurrent(REPOSITORY, JAR);
    }

    @AfterEach
    void stop() throws Exception {
        for (Process process : new Process[] {client, server}) {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(30, TimeUnit.SECONDS);
            }
        }
        http.close();
    }

    @Test
    void thePackagedJarServesTheInterfaceFromItsOwnResources() throws Exception {
        Path run = Files.createDirectories(directory.resolve("server-run"));
        int port = freePort();
        server = start("server", run, serverConfig(run, port, ""));
        URI origin = URI.create("http://127.0.0.1:" + port);
        awaitReady(server, origin, "/health/ready", run.resolve("server.out"));
        try (Stream<Path> files = Files.walk(run)) {
            assertTrue(files.noneMatch(file -> file.getFileName().toString().equals("index.html")),
                    "no interface file on disk where the server runs");
        }

        HttpResponse<String> index = get(origin, "/ui/");
        assertEquals(200, index.statusCode());
        assertTrue(index.body().contains("id=\"qraft-bootstrap\""), "the index is rendered with its bootstrap object");
        HttpResponse<String> manifest = get(origin, "/ui/asset-manifest.json");
        assertEquals(200, manifest.statusCode());
        String entry = entryFile(JSON.readTree(manifest.body()));
        assertTrue(index.body().contains("\"./" + entry + "\""), "the index links the manifest's entry " + entry);
        HttpResponse<byte[]> asset = http.send(HttpRequest.newBuilder(origin.resolve("/ui/" + entry))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, asset.statusCode(), entry);
        assertEquals("public, max-age=31536000, immutable", asset.headers().firstValue("Cache-Control").orElse(""));
        assertArrayEquals(packaged(entry), asset.body(), "the served bundle is the one packaged in the jar");
        assertEquals(200, get(origin, "/ui/services/web").statusCode(), "a view's address is answered with the index");

        server.destroyForcibly();
        server.waitFor(30, TimeUnit.SECONDS);
        SubprocessOutputAuditHelper.requireNoErrors("the packaged server", Files.readString(run.resolve("server.out")));
    }

    @Test
    void aServerWithTheInterfaceDisabledAndAClientServeNoneOfItsRoutes() throws Exception {
        String entry = entryFile(JSON.readTree(packaged("asset-manifest.json")));
        Path serverRun = Files.createDirectories(directory.resolve("server-run"));
        int serverPort = freePort();
        server = start("server", serverRun, serverConfig(serverRun, serverPort, ", \"ui\": {\"enabled\": false}"));
        URI serverOrigin = URI.create("http://127.0.0.1:" + serverPort);
        awaitReady(server, serverOrigin, "/health/ready", serverRun.resolve("server.out"));
        Path clientRun = Files.createDirectories(directory.resolve("client-run"));
        int clientPort = freePort();
        client = start("client", clientRun, clientConfig(clientRun, clientPort, serverPort));
        URI clientOrigin = URI.create("http://127.0.0.1:" + clientPort);
        awaitReady(client, clientOrigin, "/health/live", clientRun.resolve("client.out"));

        for (URI origin : List.of(serverOrigin, clientOrigin)) {
            for (String path : List.of("/ui/", "/ui/index.html", "/ui/asset-manifest.json", "/ui/" + entry,
                    "/ui/services/web")) {
                HttpResponse<String> response = get(origin, path);
                assertEquals(404, response.statusCode(), origin + path);
                assertTrue(!response.body().contains("qraft-bootstrap"), origin + path);
            }
        }
        assertEquals(200, get(serverOrigin, "/v1/catalog/services").statusCode(), "the server's API is served");

        client.destroyForcibly();
        client.waitFor(30, TimeUnit.SECONDS);
        server.destroyForcibly();
        server.waitFor(30, TimeUnit.SECONDS);
        SubprocessOutputAuditHelper.requireNoErrors("the client", Files.readString(clientRun.resolve("client.out")));
        SubprocessOutputAuditHelper.requireNoErrors("the server without the interface",
                Files.readString(serverRun.resolve("server.out")));
    }

    /** Starts the packaged jar in {@code mode} with {@code run} as its working directory and its output in a file. */
    private Process start(String mode, Path run, String config) throws IOException {
        Path file = run.resolve(mode + ".json");
        Files.writeString(file, config);
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", JAR.toString(), mode, "--config", file.toString())
                .directory(run.toFile())
                .redirectErrorStream(true)
                .redirectOutput(run.resolve(mode + ".out").toFile())
                .start();
    }

    private static String serverConfig(Path run, int httpPort, String ui) throws IOException {
        return """
                {"version": 1,
                 "server": {"id": "packaged-server", "http": {"host": "127.0.0.1", "port": %d}, "apiGrpcPort": %d,
                            "raft": {"port": %d, "nodes": {"packaged-server": "127.0.0.1:%3$d"},
                                     "electionTimeoutMs": 1500, "heartbeatIntervalMs": 300,
                                     "storage": {"type": "raftlog", "path": %s, "fsync": true}},
                            "telemetry": {"enabled": false}%s},
                 "logging": {"directory": %s}}
                """.formatted(httpPort, freePort(), freePort(), path(run.resolve("data")), ui,
                path(run.resolve("logs")));
    }

    private static String clientConfig(Path run, int httpPort, int serverPort) throws IOException {
        return """
                {"version": 1,
                 "client": {"nodeName": "packaged-client", "dataDirectory": %s, "address": "127.0.0.1",
                            "httpPort": %d, "heartbeatIntervalMs": 500, "shutdownTimeoutMs": 3000},
                 "servers": {"urls": ["http://127.0.0.1:%d"], "requestTimeoutMs": 2000},
                 "catalog": {"services": []},
                 "logging": {"directory": %s}}
                """.formatted(path(run.resolve("data")), httpPort, serverPort, path(run.resolve("logs")));
    }

    private void awaitReady(Process process, URI origin, String health, Path output) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            assertTrue(process.isAlive(), () -> "the process stopped before it was ready:\n" + read(output));
            if (status(origin, health) == 200) return;
            Thread.sleep(100);
        }
        throw new AssertionError(origin + health + " did not answer 200 within 60 s:\n" + read(output));
    }

    private int status(URI origin, String path) {
        try {
            return get(origin, path).statusCode();
        } catch (IOException | InterruptedException notYet) {
            if (notYet instanceof InterruptedException) Thread.currentThread().interrupt();
            return -1;
        }
    }

    private HttpResponse<String> get(URI origin, String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** A file below the interface's root, as the jar holds it. */
    private static byte[] packaged(String file) throws IOException {
        try (JarFile jar = new JarFile(JAR.toFile())) {
            var entry = jar.getJarEntry(UI_ROOT + file);
            assertNotNull(entry, UI_ROOT + file + " is not in " + JAR);
            try (InputStream in = jar.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    private static String entryFile(JsonNode manifest) {
        for (JsonNode chunk : manifest) {
            if (chunk.path("isEntry").asBoolean()) return chunk.path("file").asText();
        }
        throw new AssertionError("the manifest names no entry: " + manifest);
    }

    private static String path(Path file) throws IOException {
        return JSON.writeValueAsString(file.toString());
    }

    private static String read(Path output) {
        try {
            return Files.exists(output) ? Files.readString(output) : "(no output)";
        } catch (IOException unreadable) {
            return "(output unreadable: " + unreadable.getMessage() + ")";
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
