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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.mars.qraft.client.config.ClientConfiguration;
import dev.mars.qraft.common.http.JsonApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * The client's local API server: one HTTP listener, on the bind address, serving liveness, readiness and the API
 * routes, as Consul's agent serves its HTTP API on {@code client_addr}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-03-15
 * @version 1.0
 */
public final class LocalApiServer {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalApiServer.class);
    /** The request handling the local API shares with the server's API: request IDs, body limits, errors. */
    private final JsonApi api = new JsonApi(new ObjectMapper());
    private final ClientConfiguration config;
    private final BooleanSupplier readiness;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean acceptingWrites = new AtomicBoolean(true);
    private final Map<String, HttpHandler> routes = new LinkedHashMap<>();
    private HttpServer server;
    private ExecutorService executor;

    public LocalApiServer(ClientConfiguration config, BooleanSupplier readiness) {
        this.config = config;
        this.readiness = readiness;
    }

    public synchronized void start() {
        if (running.get()) return;
        try {
            server = HttpServer.create(new InetSocketAddress(config.getBindAddress(), config.getClientPort()), 0);
            executor = Executors.newVirtualThreadPerTaskExecutor();
            server.setExecutor(executor);
            server.createContext("/health/live", api.handle(exchange -> {
                if (api.requireMethod(exchange, "GET")) api.json(exchange, 200, Map.of("status", "alive"));
            }));
            server.createContext("/health/ready", api.handle(exchange -> {
                if (!api.requireMethod(exchange, "GET")) return;
                boolean ready = isReady();
                api.json(exchange, ready ? 200 : 503, Map.of("status", ready ? "ready" : "not_ready"));
            }));
            routes.forEach(server::createContext);
            server.start();
            running.set(true);
            // As Consul's agent logs its HTTP server's protocol and address when it starts it.
            InetSocketAddress bound = server.getAddress();
            LOGGER.info("Local API started: protocol=http, address={}:{}",
                    bound.getAddress().getHostAddress(), bound.getPort());
        } catch (IOException e) {
            if (executor != null) executor.close();
            server = null;
            executor = null;
            throw new IllegalStateException("Failed to start the local API on "
                    + config.getBindAddress().getHostAddress() + ":" + config.getClientPort(), e);
        }
    }

    public synchronized void shutdown() {
        running.set(false);
        if (server != null) server.stop(0);
        if (executor != null) executor.close();
        server = null;
        executor = null;
    }

    /**
     * The port the health endpoint is listening on. It differs from the configured port when that is 0,
     * which asks the system for any free port.
     *
     * @throws IllegalStateException when the service is not running
     */
    public synchronized int port() {
        if (server == null) throw new IllegalStateException("the local API is not running");
        return server.getAddress().getPort();
    }

    /**
     * The address and port the local API listens on.
     *
     * @throws IllegalStateException when the service is not running
     */
    public synchronized InetSocketAddress address() {
        if (server == null) throw new IllegalStateException("the local API is not running");
        return server.getAddress();
    }

    /**
     * Serves {@code handler} at {@code path} with the API's shared request handling, from {@link #start()}, or now
     * when the listener is already running.
     */
    public synchronized void route(String path, HttpHandler handler) {
        Objects.requireNonNull(handler, "handler");
        HttpHandler handled = api.handle(exchange -> {
            if (!isRead(exchange) && !acceptingWrites.get()) {
                api.error(exchange, 503, "shutting_down", "The client is shutting down and takes no more writes",
                        true, Map.of());
                return;
            }
            handler.handle(exchange);
        });
        routes.put(Objects.requireNonNull(path, "path"), handled);
        if (server != null) server.createContext(path, handled);
    }

    /** Stops taking API writes; reads, liveness and readiness are served until {@link #shutdown()}. */
    public void beginShutdown() {
        acceptingWrites.set(false);
    }

    /** Whether API writes are taken: until shutdown begins. */
    public boolean acceptsWrites() { return acceptingWrites.get(); }

    /** A read only looks: a GET or a HEAD. Anything else is a write. */
    private static boolean isRead(com.sun.net.httpserver.HttpExchange exchange) {
        String method = exchange.getRequestMethod();
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
    }

    public boolean isHealthy() { return running.get(); }
    public boolean isReady() { return running.get() && readiness.getAsBoolean(); }
    public String nodeName() { return config.getNodeName(); }
}
