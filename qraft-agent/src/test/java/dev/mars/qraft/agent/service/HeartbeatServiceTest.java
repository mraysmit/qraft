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

package dev.mars.qraft.agent.service;

import com.sun.net.httpserver.HttpServer;
import dev.mars.qraft.agent.AgentInfo;
import dev.mars.qraft.agent.catalog.HttpCatalogClient;
import dev.mars.qraft.agent.config.AgentConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that {@link HeartbeatService} publishes heartbeats only after agent registration.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-03-15
 * @version 1.0
 */
class HeartbeatServiceTest {
    private HttpServer server;
    private HttpCatalogClient controllerClient;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (controllerClient != null) controllerClient.close();
    }

    @Test
    void publishesHeartbeatAfterRegistration() throws Exception {
        AtomicInteger heartbeats = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/agents/register", exchange -> {
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        server.createContext("/api/v1/agents/heartbeat", exchange -> {
            heartbeats.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        AgentConfiguration config = AgentConfiguration.builder()
                .agentId("agent-1").controllerUrl("http://localhost:" + server.getAddress().getPort())
                .httpConnectionTimeout(1000).build();
        AgentRegistrationClient registration = registration(config);
        AgentInfo agent = new AgentInfo("agent-1", "host", "127.0.0.1", 8080);
        assertTrue(registration.register(agent).join());

        HeartbeatService heartbeat = new HeartbeatService(config, registration);
        assertTrue(heartbeat.sendHeartbeat().join());
        assertTrue(heartbeats.get() == 1);
    }

    @Test
    void doesNotPublishBeforeRegistration() {
        AgentConfiguration config = AgentConfiguration.builder()
                .agentId("agent-1").controllerUrl("http://localhost").build();
        AgentRegistrationClient registration = registration(config);
        assertFalse(new HeartbeatService(config, registration).sendHeartbeat().join());
    }

    private AgentRegistrationClient registration(AgentConfiguration config) {
        controllerClient = new HttpCatalogClient(HttpClient.newHttpClient(),
                new com.fasterxml.jackson.databind.ObjectMapper(), config.getControllerUrls(),
                config.getAgentId(), config.getTenant(), config.getNamespace(),
                config.getDatacenter(), config.getRegion(), Duration.ofMillis(config.getRequestTimeoutMs()));
        return new AgentRegistrationClient(controllerClient);
    }
}
