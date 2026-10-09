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

import com.sun.net.httpserver.HttpServer;
import dev.mars.qraft.common.ClientInfo;
import dev.mars.qraft.client.catalog.HttpCatalogClient;
import dev.mars.qraft.client.config.ClientConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that {@link HeartbeatService} publishes heartbeats only after client registration.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-03-15
 * @version 1.0
 */
class HeartbeatServiceTest {
    private HttpServer server;
    private HttpCatalogClient serverClient;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (serverClient != null) serverClient.close();
    }

    @Test
    void publishesHeartbeatAfterRegistration() throws Exception {
        AtomicInteger heartbeats = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/clients/register", exchange -> {
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        server.createContext("/api/v1/clients/heartbeat", exchange -> {
            heartbeats.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();

        ClientConfiguration config = ClientConfiguration.builder()
                .clientId("client-1").serverUrl("http://localhost:" + server.getAddress().getPort())
                .requestTimeoutMs(1000).build();
        RegistrationClient registration = registration(config);
        ClientInfo client = new ClientInfo("client-1", "host", "127.0.0.1", 8080);
        assertTrue(registration.register(client).join());

        HeartbeatService heartbeat = new HeartbeatService(config, registration);
        assertTrue(heartbeat.sendHeartbeat().join());
        assertTrue(heartbeats.get() == 1);
    }

    @Test
    void doesNotPublishBeforeRegistration() throws Exception {
        AtomicInteger heartbeats = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/clients/heartbeat", exchange -> {
            heartbeats.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        ClientConfiguration config = ClientConfiguration.builder()
                .clientId("client-1").serverUrl("http://localhost:" + server.getAddress().getPort()).build();
        RegistrationClient registration = registration(config);

        assertFalse(new HeartbeatService(config, registration).sendHeartbeat().join());
        assertEquals(0, heartbeats.get(), "an unregistered client sends nothing, though the server would accept it");
    }

    private RegistrationClient registration(ClientConfiguration config) {
        serverClient = new HttpCatalogClient(HttpClient.newHttpClient(),
                new com.fasterxml.jackson.databind.ObjectMapper(), config.getServerUrls(),
                config.getClientId(), config.getTenant(), config.getNamespace(),
                config.getDatacenter(), config.getRegion(), Duration.ofMillis(config.getRequestTimeoutMs()));
        return new RegistrationClient(serverClient);
    }
}
