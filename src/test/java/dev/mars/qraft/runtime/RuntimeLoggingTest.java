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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.mars.qraft.client.NodeAnswerHelper;
import dev.mars.qraft.server.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests real fresh-process startup logging, including directory selection before logger initialization.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-08
 * @version 1.0
 */
class RuntimeLoggingTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void telemetryDefaultsToServerName() {
        assertEquals("qraft-server", AppConfig.fromJson("{\"version\":1,\"server\":{}}").getServiceName());
    }

    @ParameterizedTest
    @ValueSource(strings = {"server", "client"})
    void logsUseConfiguredDirectoryAndRuntimeModeFromTheFirstEvent(String mode) throws Exception {
        Path repository = Path.of("").toAbsolutePath();
        Path retained = Files.createDirectories(repository.resolve("logs")
                .resolve("phase3-runtime-process-" + mode + "-" + UUID.randomUUID()));
        Path directory = retained.resolve("configured");
        HttpServer endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        endpoint.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        endpoint.createContext("/v1/catalog", NodeAnswerHelper::accept);
        endpoint.start();
        Process child = null;
        try {
            Map<String, Object> config = mode.equals("server")
                    ? Map.of("version", 1, "logging", Map.of("directory", directory.toString()),
                        "server", Map.of("id", "logging-fixture",
                            "http", Map.of("host", "127.0.0.1", "port", 0), "apiGrpcPort", 0,
                            "raft", Map.of("port", 0, "storage", Map.of("path", retained.resolve("raft").toString())),
                            "telemetry", Map.of("enabled", false)))
                    : Map.of("version", 1, "logging", Map.of("directory", directory.toString()),
                        "client", Map.of("id", "logging-fixture", "httpPort", 0),
                        "servers", Map.of("urls", java.util.List.of(
                            "http://127.0.0.1:" + endpoint.getAddress().getPort())));
            Path configuration = retained.resolve("config.json");
            JSON.writeValue(configuration.toFile(), config);
            Path console = retained.resolve("console.log");
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Dlogback.configurationFile=" + repository.resolve("src/main/resources/logback.xml"),
                    "-cp", classpath, RuntimeLoggingProcessFixture.class.getName(), mode,
                    "--config", configuration.toString())
                    .directory(retained.toFile()).redirectErrorStream(true).redirectOutput(console.toFile()).start();
            assertTrue(child.waitFor(40, TimeUnit.SECONDS), "startup fixture timed out");
            String output = Files.readString(console);
            System.out.print(output);
            assertEquals(0, child.exitValue(), output);
            assertFalse(output.matches("(?s).*\\bERROR\\b.*"), output);
            assertFalse(output.contains("Exception in thread"), output);
            Path textLog = directory.resolve("qraft-" + mode + ".log");
            Path jsonLog = directory.resolve("qraft-" + mode + ".json");
            assertTrue(Files.isRegularFile(textLog), "missing configured mode log: " + textLog);
            assertTrue(Files.isRegularFile(jsonLog), "missing configured JSON mode log: " + jsonLog);
            assertTrue(Files.readString(textLog).contains("PHASE3_STARTUP_MARKER"));
            assertTrue(Files.readString(jsonLog).contains("PHASE3_STARTUP_MARKER"));
            assertFalse(Files.exists(retained.resolve("logs")), "startup must not initialize fallback log files");
            try (var files = Files.list(directory)) {
                assertTrue(files.noneMatch(path -> path.getFileName().toString().contains("controller")));
            }
            if (mode.equals("server")) {
                assertTrue(Files.readString(textLog).contains("Server configuration:"),
                        "the configuration event must reach the configured file too");
            }
        } finally {
            if (child != null && child.isAlive()) {
                child.destroyForcibly();
                assertTrue(child.waitFor(10, TimeUnit.SECONDS), "child did not terminate");
            }
            endpoint.stop(0);
        }
    }
}
