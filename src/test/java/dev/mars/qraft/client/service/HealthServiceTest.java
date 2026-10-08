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

package dev.mars.qraft.client.service;

import dev.mars.qraft.client.config.AgentConfiguration;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link HealthService} local health tracking across start and shutdown, and its liveness and
 * readiness HTTP endpoints.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-09
 * @version 1.0
 */
class HealthServiceTest {
    @Test
    void tracksLocalAgentHealth() {
        AgentConfiguration config = AgentConfiguration.builder()
                .agentId("agent-1").controllerUrl("http://localhost").agentPort(0).build();
        HealthService health = new HealthService(config, () -> false);

        assertFalse(health.isHealthy());
        assertEquals("agent-1", health.agentId());
        health.start();
        assertTrue(health.isHealthy());
        health.shutdown();
        assertFalse(health.isHealthy());
    }

    @Test
    void servesLivenessAndReadinessEndpoints() throws Exception {
        AgentConfiguration config = AgentConfiguration.builder()
                .agentId("agent-health").controllerUrl("http://localhost").agentPort(0).build();
        AtomicBoolean ready = new AtomicBoolean();
        HealthService health = new HealthService(config, ready::get);
        HttpClient client = HttpClient.newHttpClient();

        try {
            health.start();
            int port = health.port();

            assertEquals(200, get(client, port, "/health/live"));
            assertEquals(503, get(client, port, "/health/ready"));
            assertEquals(503, get(client, port, "/health"));

            ready.set(true);

            assertEquals(200, get(client, port, "/health/ready"));
            assertEquals(200, get(client, port, "/health"));
        } finally {
            health.shutdown();
        }
    }

    @Test
    void reportsThePortItBoundOnlyWhileRunning() throws Exception {
        AgentConfiguration config = AgentConfiguration.builder()
                .agentId("agent-port").controllerUrl("http://localhost").agentPort(0).build();
        HealthService health = new HealthService(config, () -> true);

        assertThrows(IllegalStateException.class, health::port, "no port is bound before start");
        health.start();
        int port;
        try {
            port = health.port();
            assertTrue(port > 0, "port 0 is replaced by the port the system chose");
            assertEquals(200, get(HttpClient.newHttpClient(), port, "/health/live"));
        } finally {
            health.shutdown();
        }
        assertThrows(IllegalStateException.class, health::port, "no port is bound after shutdown");
    }

    private static int get(HttpClient client, int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

}
