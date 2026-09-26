package dev.mars.qraft.controller.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.mars.qraft.agent.AgentInfo;
import dev.mars.qraft.agent.AgentStatus;
import dev.mars.qraft.catalog.HealthCheckState;
import dev.mars.qraft.catalog.HealthObservation;
import dev.mars.qraft.catalog.ServiceHealth;
import dev.mars.qraft.catalog.ServiceInstance;
import dev.mars.qraft.catalog.ServiceInstanceId;
import dev.mars.qraft.controller.raft.RaftNode;
import dev.mars.qraft.controller.raft.CommandOutcomeUnknownException;
import dev.mars.qraft.controller.state.AgentCommand;
import dev.mars.qraft.controller.state.CatalogCommand;
import dev.mars.qraft.controller.state.RaftCommand;
import dev.mars.qraft.controller.state.RaftCommandResult;
import dev.mars.qraft.controller.state.QraftStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/** Lightweight JDK HTTP API for health and controller discovery endpoints. */
public final class HttpApiServer implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(HttpApiServer.class);
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private final int port;
    private final HttpServer server;
    private final ExecutorService executor;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final RaftNode raftNode;
    private final QraftStateStore stateStore;
    private final AtomicBoolean draining = new AtomicBoolean();
    private final Clock clock;

    public HttpApiServer(int port) throws IOException {
        this(port, null, null);
    }

    public HttpApiServer(int port, RaftNode raftNode, QraftStateStore stateStore) throws IOException {
        this(port, raftNode, stateStore, Clock.systemUTC());
    }

    public HttpApiServer(int port, RaftNode raftNode, QraftStateStore stateStore, Clock clock)
            throws IOException {
        this.port = port;
        this.clock = Objects.requireNonNull(clock, "clock");
        if ((raftNode == null) != (stateStore == null)) {
            throw new IllegalArgumentException("raftNode and stateStore must be configured together");
        }
        this.raftNode = raftNode;
        this.stateStore = stateStore;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        register("/health/live", 200, "{\"status\":\"alive\"}");
        registerHealth("/health/ready", "ready");
        registerHealth("/health", "passing");
        register("/status", 200, "{\"status\":\"running\"}");
        register("/api/v1/info", 200, "{\"version\":\"1.0.0\",\"httpPort\":" + port + "}");
        server.createContext("/raft/status", requestAware(this::raftStatus));
        server.createContext("/api/v1/agents/register", requestAware(this::registerAgent));
        server.createContext("/api/v1/agents/heartbeat", requestAware(this::heartbeatAgent));
        server.createContext("/api/v1/agents", requestAware(this::agents));
        server.createContext("/v1/agent/service/register", requestAware(this::registerService));
        server.createContext("/v1/agent/service/deregister", requestAware(this::deregisterService));
        server.createContext("/v1/agent/check/observe", requestAware(this::observeHealth));
        server.createContext("/v1/catalog/services", requestAware(this::listServices));
        server.createContext("/v1/catalog/service", requestAware(this::listServiceInstances));
        server.createContext("/v1/health/service", requestAware(this::listServiceHealth));
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
        server.createContext(path, requestAware(exchange -> {
            if (draining.get() && !path.startsWith("/health")) {
                respondError(exchange, 503, "draining", "Server is draining", true);
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                respondError(exchange, 405, "method_not_allowed", "Method not allowed", false);
                return;
            }
            respond(exchange, status, body);
        }));
    }

    private void registerHealth(String path, String healthyStatus) {
        server.createContext(path, requestAware(exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                respondError(exchange, 405, "method_not_allowed", "Method not allowed", false);
                return;
            }
            if ("/health/ready".equals(path) && raftNode != null && raftNode.isFenced()) {
                respondError(exchange, 503, "fenced", "Raft node is fenced", true);
                return;
            }
            respond(exchange, 200, "{\"status\":\"" + healthyStatus + "\"}");
        }));
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
            RequestContext context = new HeaderRequestContext(exchange);
            ServiceRegistrationRequest request = objectMapper.readerFor(ServiceRegistrationRequest.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(exchange.getRequestBody());
            ServiceInstance instance = request.toServiceInstance(context);
            RaftCommandResult<?> result = submit(CatalogCommand.register(instance));
            boolean registered = result instanceof RaftCommandResult.Success<?>;
            respondJson(exchange, registered ? 200 : 409,
                    ServiceRegistrationResponse.from(instance, registered));
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JacksonException e) {
            respondError(exchange, 400, "invalid_registration", safeMessage(e), false);
        } catch (CompletionException e) {
            respondUnavailable(exchange, e);
        }
    }

    private void raftStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respondError(exchange, 405, "method_not_allowed", "Method not allowed", false);
            return;
        }
        if (raftNode == null) {
            respondError(exchange, 503, "catalog_unavailable", "Raft state is unavailable", true);
            return;
        }
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("nodeId", raftNode.getNodeId());
        status.put("state", raftNode.getState().name());
        status.put("term", raftNode.getCurrentTerm());
        status.put("leaderId", raftNode.getLeaderId());
        status.put("commitIndex", raftNode.getCommitIndex());
        status.put("snapshotLastIndex", raftNode.getSnapshotLastIndex());
        status.put("fenced", raftNode.isFenced());
        respondJson(exchange, 200, status);
    }

    private void registerAgent(HttpExchange exchange) throws IOException {
        if (!prepareStateRequest(exchange, "POST")) return;
        try {
            AgentInfo agent = objectMapper.readValue(exchange.getRequestBody(), AgentInfo.class);
            if (agent.getAgentId() == null || agent.getAgentId().isBlank()) {
                throw new IllegalArgumentException("agentId is required");
            }
            submit(AgentCommand.register(agent));
            respondJson(exchange, 201, Map.of("registered", true, "agentId", agent.getAgentId()));
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JacksonException e) {
            respondError(exchange, 400, "invalid_agent", safeMessage(e), false);
        } catch (CompletionException e) {
            respondUnavailable(exchange, e);
        }
    }

    private void heartbeatAgent(HttpExchange exchange) throws IOException {
        if (!prepareStateRequest(exchange, "POST")) return;
        try {
            AgentHeartbeat heartbeat = objectMapper.readValue(exchange.getRequestBody(), AgentHeartbeat.class);
            if (heartbeat.agentId() == null || heartbeat.agentId().isBlank()) {
                throw new IllegalArgumentException("agentId is required");
            }
            AgentStatus status = heartbeat.status() == null || heartbeat.status().isBlank()
                    ? null : heartbeatStatus(heartbeat.status());
            Instant timestamp = heartbeat.timestamp() == null ? Instant.now() : heartbeat.timestamp();
            RaftCommandResult<?> result = submit(AgentCommand.heartbeat(
                    heartbeat.agentId(), status, timestamp, heartbeat.sequenceNumber(), heartbeat.registrationId()));
            if (result instanceof RaftCommandResult.NotFound<?>) {
                respondError(exchange, 404, "agent_not_found", "Agent not found", false,
                        Map.of("agentId", heartbeat.agentId()));
                return;
            }
            if (result instanceof RaftCommandResult.CasMismatch<?>) {
                respondError(exchange, 409, "stale_heartbeat", "Heartbeat validation failed", false,
                        Map.of("agentId", heartbeat.agentId(), "sequenceNumber", heartbeat.sequenceNumber()));
                return;
            }
            respondNoContent(exchange);
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JacksonException e) {
            respondError(exchange, 400, "invalid_heartbeat", safeMessage(e), false);
        } catch (CompletionException e) {
            respondUnavailable(exchange, e);
        }
    }

    private void agents(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("/api/v1/agents".equals(path)) {
            if (!prepareStateRequest(exchange, "GET")) return;
            respondJson(exchange, 200, stateStore.getAgents().values());
            return;
        }
        if (!prepareStateRequest(exchange, "DELETE")) return;
        String agentId = pathParameter(exchange, "/api/v1/agents/");
        if (agentId == null) {
            respondError(exchange, 400, "agent_id_required", "Agent ID is required", false);
            return;
        }
        try {
            RaftCommandResult<?> result = submit(AgentCommand.deregister(agentId));
            if (result instanceof RaftCommandResult.NotFound<?>) {
                respondError(exchange, 404, "agent_not_found", "Agent not found", false,
                        Map.of("agentId", agentId));
                return;
            }
            respondNoContent(exchange);
        } catch (CompletionException e) {
            respondUnavailable(exchange, e);
        }
    }

    private void deregisterService(HttpExchange exchange) throws IOException {
        if (!prepareCatalogRequest(exchange, "PUT")) return;
        String serviceId = pathParameter(exchange, "/v1/agent/service/deregister/");
        if (serviceId == null) {
            respondError(exchange, 400, "service_id_required", "Service ID is required", false);
            return;
        }
        try {
            DeregistrationRequest request = new DeregistrationRequest(serviceId, new HeaderRequestContext(exchange));
            RaftCommandResult<?> result = submit(CatalogCommand.deregister(request.identity()));
            boolean deregistered = result instanceof RaftCommandResult.Success<?>;
            respondJson(exchange, 200, Map.of("deregistered", deregistered, "serviceId", serviceId));
        } catch (IllegalArgumentException e) {
            respondError(exchange, 400, "invalid_registration", safeMessage(e), false);
        } catch (CompletionException e) {
            respondUnavailable(exchange, e);
        }
    }

    private void listServices(HttpExchange exchange) throws IOException {
        if (!prepareCatalogRequest(exchange, "GET")) return;
        setAppliedIndex(exchange);
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
        setAppliedIndex(exchange);
        String prefix = exchange.getHttpContext().getPath() + "/";
        String serviceName = pathParameter(exchange, prefix);
        if (serviceName == null) {
            respondError(exchange, 400, "service_name_required", "Service name is required", false);
            return;
        }
        respondJson(exchange, 200, stateStore.getServiceCatalog().instances(serviceName));
    }

    private void observeHealth(HttpExchange exchange) throws IOException {
        if (!prepareCatalogRequest(exchange, "PUT")) return;
        try {
            RequestContext context = new HeaderRequestContext(exchange);
            HealthObservationRequest request = objectMapper.readerFor(HealthObservationRequest.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(exchange.getRequestBody());
            HealthObservation observation = request.toObservation(context);
            RaftCommandResult<?> result = submit(CatalogCommand.observe(observation, clock.instant()));
            if (result instanceof RaftCommandResult.NotFound<?>) {
                respondError(exchange, 404, "service_not_found", "Service instance is not registered", false,
                        Map.of("serviceId", request.serviceId(), "checkId", request.checkId()));
                return;
            }
            if (!(result instanceof RaftCommandResult.Success<?>(HealthCheckState current))) {
                throw new IllegalStateException("Unexpected observation result: " + result);
            }
            // The state machine answers an equal or older sequence with the current state; only an exact
            // replay of the accepted observation is a success.
            if (!current.observation().equals(observation)) {
                respondError(exchange, 409, "stale_observation", "Observation is older than the accepted state",
                        false, Map.of("currentSequenceNumber", current.observation().sequenceNumber()));
                return;
            }
            setAppliedIndex(exchange);
            respondJson(exchange, 200, HealthObservationResponse.accepted(current));
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JacksonException e) {
            respondError(exchange, 400, "invalid_observation", safeMessage(e), false);
        } catch (CompletionException e) {
            respondUnavailable(exchange, e);
        }
    }

    private void listServiceHealth(HttpExchange exchange) throws IOException {
        if (!prepareCatalogRequest(exchange, "GET")) return;
        String serviceName = pathParameter(exchange, "/v1/health/service/");
        if (serviceName == null) {
            respondError(exchange, 400, "service_name_required", "Service name is required", false);
            return;
        }
        boolean passingOnly;
        try {
            passingOnly = passingFilter(exchange.getRequestURI().getRawQuery());
        } catch (IllegalArgumentException e) {
            respondError(exchange, 400, "invalid_query", safeMessage(e), false);
            return;
        }
        setAppliedIndex(exchange);
        Map<ServiceInstanceId, List<HealthCheckState>> checks = stateStore.healthChecks().stream()
                .collect(Collectors.groupingBy(state -> state.checkId().serviceInstanceId()));
        List<HealthServiceEntry> entries = stateStore.getServiceCatalog().instances(serviceName).stream()
                .filter(instance -> !passingOnly || instance.health() == ServiceHealth.PASSING)
                .map(instance -> HealthServiceEntry.from(instance,
                        checks.getOrDefault(instance.identity(), List.of())))
                .toList();
        respondJson(exchange, 200, entries);
    }

    /** Parses the only supported health query parameter: {@code passing}, {@code passing=true|false}. */
    private static boolean passingFilter(String rawQuery) {
        boolean passingOnly = false;
        if (rawQuery == null || rawQuery.isEmpty()) return false;
        for (String parameter : rawQuery.split("&")) {
            if (parameter.isEmpty()) continue;
            int separator = parameter.indexOf('=');
            String name = URLDecoder.decode(separator < 0 ? parameter : parameter.substring(0, separator),
                    StandardCharsets.UTF_8);
            String value = separator < 0 ? "" : URLDecoder.decode(parameter.substring(separator + 1),
                    StandardCharsets.UTF_8);
            if (!"passing".equals(name)) {
                throw new IllegalArgumentException("Unsupported query parameter: " + name);
            }
            passingOnly = switch (value) {
                case "", "true" -> true;
                case "false" -> false;
                default -> throw new IllegalArgumentException("passing must be true or false");
            };
        }
        return passingOnly;
    }

    private void setAppliedIndex(HttpExchange exchange) {
        exchange.getResponseHeaders().set("X-Qraft-Index", Long.toString(stateStore.getLastAppliedIndex()));
    }

    private boolean prepareCatalogRequest(HttpExchange exchange, String expectedMethod) throws IOException {
        return prepareStateRequest(exchange, expectedMethod);
    }

    private boolean prepareStateRequest(HttpExchange exchange, String expectedMethod) throws IOException {
        if (draining.get()) {
            respondError(exchange, 503, "draining", "Server is draining", true);
            return false;
        }
        if (!expectedMethod.equalsIgnoreCase(exchange.getRequestMethod())) {
            respondError(exchange, 405, "method_not_allowed", "Method not allowed", false);
            return false;
        }
        if (raftNode == null) {
            respondError(exchange, 503, "catalog_unavailable", "Catalog is unavailable", true);
            return false;
        }
        return true;
    }

    private RaftCommandResult<?> submit(RaftCommand command) {
        return raftNode.submitCommand(command).toCompletionStage().toCompletableFuture()
                .orTimeout(5, TimeUnit.SECONDS)
                .join();
    }

    private static AgentStatus heartbeatStatus(String value) {
        if ("passing".equalsIgnoreCase(value)) return AgentStatus.HEALTHY;
        AgentStatus status = AgentStatus.fromValue(value);
        return switch (status) {
            case ACTIVE, IDLE -> AgentStatus.HEALTHY;
            case OVERLOADED -> AgentStatus.DEGRADED;
            case DRAINING -> AgentStatus.MAINTENANCE;
            default -> status;
        };
    }

    private static void respondNoContent(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    private void respondJson(HttpExchange exchange, int status, Object body) throws IOException {
        respond(exchange, status, objectMapper.writeValueAsString(body));
    }

    private void respondUnavailable(HttpExchange exchange, CompletionException error) throws IOException {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        String leaderId = raftNode == null ? null : raftNode.getLeaderId();
        if (cause instanceof TimeoutException || cause instanceof CommandOutcomeUnknownException) {
            if (leaderId != null && !leaderId.isBlank()) {
                exchange.getResponseHeaders().set("X-Qraft-Leader-Id", leaderId);
            }
            Map<String, Object> details = leaderId == null || leaderId.isBlank()
                    ? Map.of() : Map.of("leaderId", leaderId);
            respondError(exchange, 503, "outcome_unknown", safeMessage(cause), true, details);
            return;
        }
        if (leaderId == null || leaderId.isBlank()) {
            respondError(exchange, 503, "leader_unavailable", safeMessage(cause), true);
            return;
        }
        exchange.getResponseHeaders().set("X-Qraft-Leader-Id", leaderId);
        respondError(exchange, 503, "leader_unavailable", safeMessage(cause), true,
                Map.of("leaderId", leaderId));
    }

    private HttpHandler requestAware(HttpHandler delegate) {
        return exchange -> {
            String requestId = exchange.getRequestHeaders().getFirst(REQUEST_ID_HEADER);
            if (requestId == null || requestId.isBlank()) requestId = UUID.randomUUID().toString();
            exchange.getResponseHeaders().set(REQUEST_ID_HEADER, requestId);
            try (MDC.MDCCloseable ignored = MDC.putCloseable("requestId", requestId)) {
                LOG.debug("HTTP request: method={}, path={}", exchange.getRequestMethod(),
                        exchange.getRequestURI().getPath());
                delegate.handle(exchange);
            }
        };
    }

    private void respondError(HttpExchange exchange, int status, String code, String message,
                              boolean retryable) throws IOException {
        respondError(exchange, status, code, message, retryable, Map.of());
    }

    private void respondError(HttpExchange exchange, int status, String code, String message,
                              boolean retryable, Map<String, ?> details) throws IOException {
        ErrorResponse error = new ErrorResponse(code, message, retryable, MDC.get("requestId"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", error.code());
        body.put("error", error.code());
        body.put("message", error.message());
        body.put("retryable", error.retryable());
        body.put("requestId", error.requestId());
        body.putAll(details);
        respondJson(exchange, status, body);
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

    private record AgentHeartbeat(String agentId, Instant timestamp, long sequenceNumber, String status,
                                  String registrationId) {
    }
}
