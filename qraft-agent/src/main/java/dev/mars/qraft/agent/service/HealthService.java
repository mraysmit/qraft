package dev.mars.qraft.agent.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.mars.qraft.agent.config.AgentConfiguration;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Local health state for the discovery agent. */
public final class HealthService {
    private final AgentConfiguration config;
    private final BooleanSupplier readiness;
    private final AtomicBoolean running = new AtomicBoolean();
    private HttpServer server;
    private ExecutorService executor;

    public HealthService(AgentConfiguration config, BooleanSupplier readiness) {
        this.config = config;
        this.readiness = readiness;
    }

    public synchronized void start() {
        if (running.get()) return;
        try {
            server = HttpServer.create(new InetSocketAddress(config.getAgentPort()), 0);
            executor = Executors.newVirtualThreadPerTaskExecutor();
            server.setExecutor(executor);
            server.createContext("/health/live", exchange -> respond(exchange, 200, "{\"status\":\"alive\"}"));
            server.createContext("/health/ready", exchange -> {
                boolean ready = isReady();
                int status = ready ? 200 : 503;
                respond(exchange, status, ready ? "{\"status\":\"ready\"}" : "{\"status\":\"not_ready\"}");
            });
            server.createContext("/health", exchange -> {
                boolean ready = isReady();
                int status = ready ? 200 : 503;
                respond(exchange, status, ready ? "{\"status\":\"passing\"}" : "{\"status\":\"starting\"}");
            });
            server.start();
            running.set(true);
        } catch (IOException e) {
            if (executor != null) executor.close();
            server = null;
            executor = null;
            throw new IllegalStateException("Failed to start agent health server on port " + config.getAgentPort(), e);
        }
    }

    public synchronized void shutdown() {
        running.set(false);
        if (server != null) server.stop(0);
        if (executor != null) executor.close();
        server = null;
        executor = null;
    }

    public boolean isHealthy() { return running.get(); }
    public boolean isReady() { return running.get() && readiness.getAsBoolean(); }
    public String agentId() { return config.getAgentId(); }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            byte[] methodBody = "{\"error\":\"method_not_allowed\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(405, methodBody.length);
            try (var output = exchange.getResponseBody()) { output.write(methodBody); }
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
