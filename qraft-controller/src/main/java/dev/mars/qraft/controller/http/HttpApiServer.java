package dev.mars.qraft.controller.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.mars.qraft.catalog.ServiceInstance;
import dev.mars.qraft.controller.raft.RaftNode;
import dev.mars.qraft.controller.state.CatalogCommand;
import dev.mars.qraft.controller.state.RaftCommandResult;
import dev.mars.qraft.controller.state.QraftStateStore;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Lightweight JDK HTTP API for health and controller discovery endpoints. */
public final class HttpApiServer implements AutoCloseable {
    private final int port;
    private final HttpServer server;
    private final ExecutorService executor;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final RaftNode raftNode;
    private final QraftStateStore stateStore;
    private final AtomicBoolean draining = new AtomicBoolean();

    public HttpApiServer(int port) throws IOException {
        this(port, null, null);
    }

    public HttpApiServer(int port, RaftNode raftNode, QraftStateStore stateStore) throws IOException {
        this.port = port;
        if ((raftNode == null) != (stateStore == null)) {
            throw new IllegalArgumentException("raftNode and stateStore must be configured together");
        }
        this.raftNode = raftNode;
        this.stateStore = stateStore;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        register("/health/live", 200, "{\"status\":\"alive\"}");
        register("/health/ready", 200, "{\"status\":\"ready\"}");
        register("/health", 200, "{\"status\":\"passing\"}");
        register("/status", 200, "{\"status\":\"running\"}");
        register("/api/v1/info", 200, "{\"version\":\"1.0.0\",\"httpPort\":" + port + "}");
        server.createContext("/v1/agent/service/register", this::registerService);
        server.createContext("/v1/agent/service/deregister", this::deregisterService);
        server.createContext("/v1/catalog/services", this::listServices);
        server.createContext("/v1/catalog/service", this::listServiceInstances);
        server.createContext("/v1/health/service", this::listServiceInstances);
    }

    public CompletableFuture<Void> start() {
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<Void> stop() {
        server.stop(0);
        executor.shutdown();
        return CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<Void> enterDrainMode() {
        draining.set(true);
        return CompletableFuture.completedFuture(null);
    }

    public boolean isDraining() { return draining.get(); }
    public int port() { return server.getAddress().getPort(); }
    @Override public void close() {
        server.stop(0);
        executor.shutdown();
    }

    private void register(String path, int status, String body) {
        server.createContext(path, exchange -> {
            if (draining.get() && !path.startsWith("/health")) {
                respond(exchange, 503, "{\"status\":\"draining\"}");
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"method_not_allowed\"}");
                return;
            }
            respond(exchange, status, body);
        });
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private void registerService(HttpExchange exchange) throws IOException {
        if (!prepareCatalogRequest(exchange, "PUT")) return;
        try {
            ServiceInstance instance = objectMapper.readValue(exchange.getRequestBody(), ServiceInstance.class);
            RaftCommandResult<?> result = submit(CatalogCommand.register(instance));
            respondJson(exchange, result instanceof RaftCommandResult.Success<?> ? 200 : 409,
                    Map.of("registered", result instanceof RaftCommandResult.Success<?>, "serviceId", instance.serviceId()));
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JacksonException e) {
            respondJson(exchange, 400, Map.of("error", "invalid_registration", "message", safeMessage(e)));
        } catch (CompletionException e) {
            respondUnavailable(exchange, e);
        }
    }

    private void deregisterService(HttpExchange exchange) throws IOException {
        if (!prepareCatalogRequest(exchange, "PUT")) return;
        String serviceId = pathParameter(exchange, "/v1/agent/service/deregister/");
        if (serviceId == null) {
            respondJson(exchange, 400, Map.of("error", "service_id_required"));
            return;
        }
        try {
            RaftCommandResult<?> result = submit(CatalogCommand.deregister(serviceId));
            int status = result instanceof RaftCommandResult.NotFound<?> ? 404 : 200;
            respondJson(exchange, status, Map.of("deregistered", status == 200, "serviceId", serviceId));
        } catch (CompletionException e) {
            respondUnavailable(exchange, e);
        }
    }

    private void listServices(HttpExchange exchange) throws IOException {
        if (!prepareCatalogRequest(exchange, "GET")) return;
        Map<String, List<String>> services = new LinkedHashMap<>();
        for (String service : stateStore.getServiceCatalog().services()) {
            List<String> tags = stateStore.getServiceCatalog().instances(service).stream()
                    .flatMap(instance -> instance.tags().stream()).distinct().sorted().toList();
            services.put(service, tags);
        }
        respondJson(exchange, 200, services);
    }

    private void listServiceInstances(HttpExchange exchange) throws IOException {
        if (!prepareCatalogRequest(exchange, "GET")) return;
        String prefix = exchange.getHttpContext().getPath() + "/";
        String serviceName = pathParameter(exchange, prefix);
        if (serviceName == null) {
            respondJson(exchange, 400, Map.of("error", "service_name_required"));
            return;
        }
        respondJson(exchange, 200, stateStore.getServiceCatalog().instances(serviceName));
    }

    private boolean prepareCatalogRequest(HttpExchange exchange, String expectedMethod) throws IOException {
        if (draining.get()) {
            respond(exchange, 503, "{\"status\":\"draining\"}");
            return false;
        }
        if (!expectedMethod.equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "{\"error\":\"method_not_allowed\"}");
            return false;
        }
        if (raftNode == null) {
            respond(exchange, 503, "{\"error\":\"catalog_unavailable\"}");
            return false;
        }
        return true;
    }

    private RaftCommandResult<?> submit(CatalogCommand command) {
        return raftNode.submitCommand(command).toCompletionStage().toCompletableFuture().join();
    }

    private void respondJson(HttpExchange exchange, int status, Object body) throws IOException {
        respond(exchange, status, objectMapper.writeValueAsString(body));
    }

    private void respondUnavailable(HttpExchange exchange, CompletionException error) throws IOException {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        respondJson(exchange, 503, Map.of("error", "leader_unavailable", "message", safeMessage(cause)));
    }

    private static String pathParameter(HttpExchange exchange, String prefix) {
        String path = exchange.getRequestURI().getPath();
        if (!path.startsWith(prefix) || path.length() == prefix.length()) return null;
        String value = URLDecoder.decode(path.substring(prefix.length()), StandardCharsets.UTF_8);
        return value.isBlank() || value.contains("/") ? null : value;
    }

    private static String safeMessage(Throwable error) {
        return Objects.toString(error.getMessage(), error.getClass().getSimpleName());
    }
}
