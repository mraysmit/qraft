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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.mars.qraft.common.Node;
import dev.mars.qraft.common.http.JsonApi;
import dev.mars.qraft.state.catalog.HealthCheckState;
import dev.mars.qraft.state.catalog.HealthObservation;
import dev.mars.qraft.state.catalog.ServiceInstance;
import dev.mars.qraft.common.concurrent.Deadlines;
import dev.mars.qraft.state.catalog.ServiceInstanceId;
import dev.mars.qraft.state.catalog.ServiceKey;
import dev.mars.qraft.raft.MembershipService;
import dev.mars.qraft.raft.RaftConfiguration;
import dev.mars.qraft.raft.grpc.MembershipResponse;
import dev.mars.qraft.raft.grpc.RemoveServerRequest;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftStatus;
import dev.mars.qraft.server.ui.AdminUiConfig;
import dev.mars.qraft.server.ui.AdminUiHandler;
import dev.mars.qraft.server.ui.UiAssets;
import dev.mars.qraft.raft.CommandOutcomeUnknownException;
import dev.mars.qraft.state.NodeCommand;
import dev.mars.qraft.state.CatalogCommand;
import dev.mars.qraft.raft.RaftCommand;
import dev.mars.qraft.raft.RaftCommandResult;
import dev.mars.qraft.state.QraftStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Lightweight JDK HTTP API for health and server discovery endpoints.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-03-15
 * @version 1.0
 */
public final class HttpApiServer implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(HttpApiServer.class);
    private final int port;
    private final AdminUiConfig adminUi;
    private final HttpServer server;
    private final ExecutorService executor;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    /** The request handling this API shares with the client's local API: request IDs, body limits, errors. */
    private final JsonApi api = new JsonApi(objectMapper);
    private final RaftNode raftNode;
    private final QraftStateStore stateStore;
    private final AtomicBoolean draining = new AtomicBoolean();
    private final Clock clock;
    private final Duration raftTimeout;
    private final MembershipService membership;

    /** How long a request waits for the Raft node to answer a status read or commit a write. */
    public static final Duration DEFAULT_RAFT_TIMEOUT = Duration.ofSeconds(5);

    public HttpApiServer(int port) throws IOException {
        this(port, null, null);
    }

    public HttpApiServer(int port, RaftNode raftNode, QraftStateStore stateStore) throws IOException {
        this(port, raftNode, stateStore, Clock.systemUTC());
    }

    /** A server without the administrative interface. */
    public HttpApiServer(int port, RaftNode raftNode, QraftStateStore stateStore, Clock clock)
            throws IOException {
        this(port, raftNode, stateStore, clock, AdminUiConfig.disabled(), null);
    }

    /**
     * A server that also serves the administrative interface from {@code assets} at {@code ui.path()} when
     * {@code ui} is enabled; a disabled interface registers no route and needs no assets.
     */
    public HttpApiServer(int port, RaftNode raftNode, QraftStateStore stateStore, Clock clock,
                         AdminUiConfig ui, UiAssets assets) throws IOException {
        this(port, raftNode, stateStore, clock, ui, assets, DEFAULT_RAFT_TIMEOUT);
    }

    /**
     * @param raftTimeout how long a request waits for the Raft node to answer a status read or commit a
     *                    write before answering that the outcome is unknown; it must be positive
     */
    public HttpApiServer(int port, RaftNode raftNode, QraftStateStore stateStore, Clock clock,
                         AdminUiConfig ui, UiAssets assets, Duration raftTimeout) throws IOException {
        this(port, raftNode, stateStore, clock, ui, assets, raftTimeout, null);
    }

    /**
     * @param membership serves the Raft operator's removals; without it they are answered as unavailable, and
     *                   the configuration is still listed
     */
    public HttpApiServer(int port, RaftNode raftNode, QraftStateStore stateStore, Clock clock,
                         AdminUiConfig ui, UiAssets assets, Duration raftTimeout, MembershipService membership)
            throws IOException {
        this.membership = membership;
        this.raftTimeout = Objects.requireNonNull(raftTimeout, "raftTimeout");
        if (raftTimeout.isNegative() || raftTimeout.isZero()) {
            throw new IllegalArgumentException("raftTimeout must be positive: " + raftTimeout);
        }
        this.port = port;
        this.adminUi = Objects.requireNonNull(ui, "ui");
        this.clock = Objects.requireNonNull(clock, "clock");
        if ((raftNode == null) != (stateStore == null)) {
            throw new IllegalArgumentException("raftNode and stateStore must be configured together");
        }
        this.raftNode = raftNode;
        this.stateStore = stateStore;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/health/live", requestAware(this::liveness));
        server.createContext("/health/ready", requestAware(this::readiness));
        if (ui.enabled()) {
            server.createContext(ui.path(), requestAware(new AdminUiHandler(ui.path(),
                    Objects.requireNonNull(assets, "assets are required for an enabled interface"))));
        }
        server.createContext("/raft/status", requestAware(this::raftStatus));
        server.createContext("/v1/operator/raft/configuration", requestAware(this::raftConfiguration));
        server.createContext("/v1/operator/raft/peer", requestAware(this::removeRaftPeer));
        server.createContext("/v1/catalog/register", requestAware(this::registerInCatalog));
        server.createContext("/v1/catalog/deregister", requestAware(this::deregisterFromCatalog));
        server.createContext("/v1/catalog/node/heartbeat", requestAware(this::heartbeatNode));
        server.createContext("/v1/catalog/nodes", requestAware(this::listNodes));
        server.createContext("/v1/catalog/check/observe", requestAware(this::observeHealth));
        server.createContext("/v1/catalog/node-services", requestAware(this::listNodeServices));
        server.createContext("/v1/catalog/services", requestAware(this::listServices));
        server.createContext("/v1/catalog/service", requestAware(this::listServiceInstances));
        server.createContext("/v1/health/service", requestAware(this::listServiceHealth));
    }

    public CompletableFuture<Void> start() {
        server.start();
        if (adminUi.enabled()) {
            LOG.warn("Administrative interface enabled at {} and unauthenticated: it is read-only and serves only "
                    + "data the public API already exposes", adminUi.path());
        }
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
    /** The address and port this server listens on. */
    public java.net.InetSocketAddress address() { return server.getAddress(); }
    @Override public void close() {
        server.stop(0);
        executor.shutdown();
    }

    /** Answers that the process is alive, whatever its state: liveness is served while draining too. */
    private void liveness(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respondError(exchange, 405, "method_not_allowed", "Method not allowed", false);
            return;
        }
        respond(exchange, 200, "{\"status\":\"alive\"}");
    }

    /** Answers whether this server meets every readiness condition, naming the ones it does not. */
    private void readiness(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respondError(exchange, 405, "method_not_allowed", "Method not allowed", false);
            return;
        }
        List<String> failed = unmetReadinessConditions();
        if (!failed.isEmpty()) {
            respondError(exchange, 503, "not_ready", "Server is not ready: " + String.join(", ", failed),
                    true, Map.of("conditions", failed));
            return;
        }
        respond(exchange, 200, "{\"status\":\"ready\"}");
    }

    /**
     * The readiness conditions this server does not meet, in a fixed order: {@code draining};
     * {@code unavailable} when its Raft state cannot be read; {@code fenced}, or else {@code recovering} while
     * recovery has not finished; {@code removed} once its removal from the configuration has committed; and
     * {@code no_leader} unless it is the leader or a follower of a known leader.
     * A server without a Raft node is judged on draining alone.
     */
    private List<String> unmetReadinessConditions() {
        List<String> failed = new ArrayList<>();
        if (draining.get()) failed.add("draining");
        if (raftNode == null) return failed;
        RaftStatus status;
        try {
            status = Deadlines.bound(raftNode.status(),
                    raftTimeout.toNanos(), TimeUnit.NANOSECONDS).join();
        } catch (CompletionException unavailable) {
            failed.add("unavailable");
            return failed;
        }
        if (status.fenced()) failed.add("fenced");
        else if (!status.running()) failed.add("recovering");
        if (status.removed()) failed.add("removed");
        if (!status.knowsLeader()) failed.add("no_leader");
        return failed;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
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
        RaftStatus current;
        try {
            // Read on the node's state loop: fields read one by one from here could mix two moments.
            current = Deadlines.bound(raftNode.status(),
                    raftTimeout.toNanos(), TimeUnit.NANOSECONDS).join();
        } catch (CompletionException unavailable) {
            respondError(exchange, 503, "raft_unavailable", "Raft state is unavailable", true);
            return;
        }
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("nodeId", current.nodeId());
        status.put("serverId", current.serverId());
        status.put("state", current.state().name());
        status.put("term", current.term());
        status.put("leaderId", current.leaderId());
        status.put("commitIndex", current.commitIndex());
        status.put("lastApplied", current.lastApplied());
        status.put("lastLogIndex", current.lastLogIndex());
        status.put("snapshotLastIndex", current.snapshotLastIndex());
        status.put("fenced", current.fenced());
        status.put("fenceReason", current.fenceReason());
        status.put("removed", current.removed());
        respondJson(exchange, 200, status);
    }

    /**
     * Lists the Raft configuration as this server holds it, after Consul's
     * {@code GET /v1/operator/raft/configuration?stale}: each server's ID, name, address, whether it votes, and
     * whether it is the leader this server knows. Open without a token, like {@code /raft/status}.
     */
    private void raftConfiguration(HttpExchange exchange) throws IOException {
        if (!prepareStateRequest(exchange, "GET")) return;
        Optional<RaftConfiguration> configuration = raftNode.getConfiguration();
        if (configuration.isEmpty()) {
            respondError(exchange, 503, "not_configured", "This server has no Raft configuration yet", true);
            return;
        }
        String leaderName = raftNode.getLeaderId();
        List<Map<String, Object>> servers = new ArrayList<>();
        for (RaftConfiguration.Server server : configuration.get().servers()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("serverId", server.serverId());
            entry.put("name", server.name());
            entry.put("address", server.address());
            entry.put("voter", server.voter());
            entry.put("leader", server.name().equals(leaderName));
            servers.add(entry);
        }
        respondJson(exchange, 200, Map.of("servers", servers));
    }

    /**
     * Removes a server, named by {@code id} or {@code name}, after Consul's {@code DELETE /v1/operator/raft/peer}.
     * It needs the operator token, as {@code X-Qraft-Token} or {@code Authorization: Bearer}. Any server forwards
     * the removal to the leader.
     */
    private void removeRaftPeer(HttpExchange exchange) throws IOException {
        if (!prepareStateRequest(exchange, "DELETE")) return;
        if (membership == null) {
            respondError(exchange, 503, "membership_unavailable", "This server does not serve membership changes",
                    false);
            return;
        }
        Map<String, String> query;
        try {
            query = operatorQuery(exchange.getRequestURI().getRawQuery());
        } catch (IllegalArgumentException invalid) {
            respondError(exchange, 400, "invalid_request", safeMessage(invalid), false);
            return;
        }
        String id = query.getOrDefault("id", "");
        String name = query.getOrDefault("name", "");
        if (id.isBlank() == name.isBlank()) {
            respondError(exchange, 400, "invalid_request", "Name the server to remove by id or by name, not both",
                    false);
            return;
        }
        RemoveServerRequest request = RemoveServerRequest.newBuilder().setServerId(id).setName(name)
                .setToken(operatorToken(exchange)).build();
        MembershipResponse answer;
        try {
            answer = Deadlines.bound(membership.remove(request),
                    MembershipService.TIMEOUT_SECONDS * 2, TimeUnit.SECONDS).join();
        } catch (CompletionException unavailable) {
            respondUnavailable(exchange, unavailable);
            return;
        }
        switch (answer.getStatus()) {
            case REMOVED -> respondJson(exchange, 200, Map.of("status", answer.getStatus().name(),
                    "message", answer.getMessage()));
            case UNAUTHORIZED -> respondError(exchange, 403, "permission_denied", answer.getMessage(), false);
            case NOT_FOUND -> respondError(exchange, 404, "not_found", answer.getMessage(), false);
            case NO_LEADER -> respondError(exchange, 503, "leader_unavailable", answer.getMessage(), true);
            default -> respondError(exchange, 409, "refused", answer.getMessage(), false);
        }
    }

    /** The operator token, from {@code X-Qraft-Token} or else a bearer {@code Authorization} header. */
    private static String operatorToken(HttpExchange exchange) {
        String token = exchange.getRequestHeaders().getFirst("X-Qraft-Token");
        if (token != null) return token;
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authorization.substring(7).trim();
        }
        return "";
    }

    /** Parses {@code id} and {@code name}, the only parameters a removal takes. */
    private static Map<String, String> operatorQuery(String rawQuery) {
        Map<String, String> parameters = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return parameters;
        for (String parameter : rawQuery.split("&")) {
            if (parameter.isEmpty()) continue;
            int separator = parameter.indexOf('=');
            String name = URLDecoder.decode(separator < 0 ? parameter : parameter.substring(0, separator),
                    StandardCharsets.UTF_8);
            String value = separator < 0 ? "" : URLDecoder.decode(parameter.substring(separator + 1),
                    StandardCharsets.UTF_8);
            if (!name.equals("id") && !name.equals("name")) {
                throw new IllegalArgumentException("Unsupported query parameter: " + name);
            }
            parameters.put(name, value);
        }
        return parameters;
    }

    /**
     * Registers what the body describes for the node that {@code X-Qraft-Node} names, after Consul's
     * {@code PUT /v1/catalog/register}: the node under {@code node}, a service under {@code service}, or both. The
     * whole body is checked before anything is replicated, so a body that cannot be accepted registers neither half.
     * The node is committed first, then the service.
     */
    private void registerInCatalog(HttpExchange exchange) throws IOException {
        if (!prepareNodeRequest(exchange, "PUT")) return;
        try {
            RequestContext context = new HeaderRequestContext(exchange);
            CatalogRegistrationRequest request = objectMapper.readerFor(CatalogRegistrationRequest.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(exchange.getRequestBody());
            if (request == null) throw new IllegalArgumentException("The body must be a JSON object");
            request.requireSomethingToRegister();
            Optional<Node> node = request.toNode(context);
            ServiceInstance instance = request.service() == null ? null
                    : request.service().toServiceInstance(context);
            List<String> declaredCheckIds = request.service() == null ? null : request.service().declaredCheckIds();
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("node", context.nodeId());
            if (node.isPresent()) {
                RaftCommandResult<?> registered = submit(NodeCommand.register(node.get(), clock.instant()));
                if (refusedAsReserved(exchange, context.nodeId(), registered)) return;
                answer.put("registered", true);
            }
            if (instance != null) {
                RaftCommandResult<?> result = submit(
                        CatalogCommand.register(instance, declaredCheckIds, context.nodeUuid()));
                if (refusedAsReserved(exchange, context.nodeId(), result)) return;
                if (!(result instanceof RaftCommandResult.Success<?>)) {
                    respondError(exchange, 409, "registration_rejected",
                            "The replicated catalog did not accept the registration", false);
                    return;
                }
                answer.put("service", ServiceRegistrationResponse.from(instance, true));
            }
            setAppliedIndex(exchange);
            respondJson(exchange, 200, answer);
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JacksonException e) {
            respondError(exchange, 400, "invalid_registration", safeMessage(e), false);
        } catch (CompletionException e) {
            respondUnavailable(exchange, e);
        }
    }

    /** Answers {@code result} when it refuses a write because a node of another ID holds the name. */
    private boolean refusedAsReserved(HttpExchange exchange, String name, RaftCommandResult<?> result)
            throws IOException {
        if (!(result instanceof RaftCommandResult.CasMismatch<?> refused && refused.entity() instanceof Node holder)) {
            return false;
        }
        respondNameReserved(exchange, name, holder);
        return true;
    }

    /**
     * Refuses a registration under a name that {@code holder}, a node of another ID, holds, in the words of Consul's
     * refusal. The name stays held until its holder is unreachable, so the refusal is not retryable.
     */
    private void respondNameReserved(HttpExchange exchange, String name, Node holder) throws IOException {
        String heldBy = holder.id() == null ? "a node without an ID" : "node " + holder.id();
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("name", holder.name());
        described.put("id", holder.id());
        respondError(exchange, 409, "node_name_reserved",
                "Node name " + name + " is reserved by " + heldBy + " with name " + holder.name(), false,
                Map.of("node", name, "holder", described));
    }

    /**
     * Deregisters from the node that {@code X-Qraft-Node} names, after Consul's {@code PUT /v1/catalog/deregister}:
     * {@code {"serviceId": ...}} removes that service and keeps the node, and a body that names no service removes
     * the node itself with everything registered on it. The service ID must be a non-blank string, so that a missing
     * value cannot remove the node instead. Both are idempotent.
     */
    private void deregisterFromCatalog(HttpExchange exchange) throws IOException {
        if (!prepareNodeRequest(exchange, "PUT")) return;
        try {
            RequestContext context = new HeaderRequestContext(exchange);
            com.fasterxml.jackson.databind.JsonNode body = objectMapper.readTree(exchange.getRequestBody());
            if (body == null || !body.isObject()) {
                throw new IllegalArgumentException("The body must be a JSON object");
            }
            if (!body.isEmpty()) {
                String field = body.fieldNames().next();
                if (body.size() > 1 || !field.equals("serviceId")) {
                    throw new IllegalArgumentException("Unsupported field: " + field);
                }
                com.fasterxml.jackson.databind.JsonNode value = body.get("serviceId");
                if (!value.isTextual() || value.textValue().isBlank()) {
                    throw new IllegalArgumentException("serviceId must be a non-blank string");
                }
                String serviceId = value.textValue();
                RaftCommandResult<?> removed = submit(CatalogCommand.deregister(
                        new DeregistrationRequest(serviceId, context).identity(), context.nodeUuid()));
                if (refusedAsReserved(exchange, context.nodeId(), removed)) return;
                setAppliedIndex(exchange);
                Map<String, Object> answer = new LinkedHashMap<>();
                answer.put("node", context.nodeId());
                answer.put("serviceId", serviceId);
                answer.put("deregistered", removed instanceof RaftCommandResult.Success<?>);
                respondJson(exchange, 200, answer);
                return;
            }
            RaftCommandResult<?> result = submit(
                    new NodeCommand.Deregister(context.nodeId(), clock.instant(), context.nodeUuid()));
            if (refusedAsReserved(exchange, context.nodeId(), result)) return;
            setAppliedIndex(exchange);
            respondJson(exchange, 200, nodeAnswer(context.nodeId(), "deregistered",
                    result instanceof RaftCommandResult.Success<?>));
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JacksonException e) {
            respondError(exchange, 400, "invalid_deregistration", safeMessage(e), false);
        } catch (CompletionException e) {
            respondUnavailable(exchange, e);
        }
    }

    /** Records a heartbeat of the node that {@code X-Qraft-Node} names, which makes the node healthy. */
    private void heartbeatNode(HttpExchange exchange) throws IOException {
        if (!prepareNodeRequest(exchange, "PUT")) return;
        try {
            RequestContext context = new HeaderRequestContext(exchange);
            NodeHeartbeatRequest heartbeat = objectMapper.readerFor(NodeHeartbeatRequest.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(exchange.getRequestBody());
            if (heartbeat == null) throw new IllegalArgumentException("The body must be a JSON object");
            // Membership expiry is measured from the server's receipt time; a heartbeat carries no time.
            RaftCommandResult<?> result = submit(NodeCommand.heartbeat(context.nodeId(), null, clock.instant(),
                    heartbeat.sequenceNumber(), heartbeat.registrationId()));
            if (result instanceof RaftCommandResult.NotFound<?>) {
                respondError(exchange, 404, "node_not_found", "Node is not registered", false,
                        Map.of("node", context.nodeId()));
                return;
            }
            if (result instanceof RaftCommandResult.CasMismatch<?> refused) {
                // A heartbeat of a registration that a later one replaced, a takeover among them, tells its client
                // to register again; one that is only out of order does not. The current registration is not told.
                if (refused.entity() instanceof Node current && heartbeat.registrationId() != null
                        && !heartbeat.registrationId()
                                .equals(current.metadata().get(Node.REGISTRATION_ID_METADATA_KEY))) {
                    respondError(exchange, 409, "registration_superseded",
                            "A later registration of the node replaced this one; register again", false,
                            Map.of("node", context.nodeId()));
                    return;
                }
                respondError(exchange, 409, "stale_heartbeat", "Heartbeat validation failed", false,
                        Map.of("node", context.nodeId(), "sequenceNumber", heartbeat.sequenceNumber()));
                return;
            }
            setAppliedIndex(exchange);
            respondJson(exchange, 200, nodeAnswer(context.nodeId(), "accepted", true));
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JacksonException e) {
            respondError(exchange, 400, "invalid_heartbeat", safeMessage(e), false);
        } catch (CompletionException e) {
            respondUnavailable(exchange, e);
        }
    }

    /** Lists the registered nodes in name order, after Consul's {@code GET /v1/catalog/nodes}. */
    private void listNodes(HttpExchange exchange) throws IOException {
        if (!prepareNodeRequest(exchange, "GET")) return;
        setAppliedIndex(exchange);
        respondJson(exchange, 200, stateStore.getNodes().values().stream().map(NodeEntry::from).toList());
    }

    /**
     * Prepares a request to a node route. A node route has no path parameter, so any longer path that the
     * HTTP server matched by prefix is answered {@code not_found}.
     */
    private boolean prepareNodeRequest(HttpExchange exchange, String expectedMethod) throws IOException {
        if (!exchange.getRequestURI().getPath().equals(exchange.getHttpContext().getPath())) {
            respondError(exchange, 404, "not_found", "No such resource", false);
            return false;
        }
        return prepareStateRequest(exchange, expectedMethod);
    }

    /** The answer of a node write: the node's name, then what happened to it. */
    private static Map<String, Object> nodeAnswer(String node, String outcome, boolean value) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("node", node);
        answer.put(outcome, value);
        return answer;
    }

    private void listServices(HttpExchange exchange) throws IOException {
        if (!prepareCatalogRequest(exchange, "GET")) return;
        ReadScope scope = readScope(exchange);
        if (scope == null) return;
        setAppliedIndex(exchange);
        Map<String, List<String>> services = new LinkedHashMap<>();
        for (String service : stateStore.getServiceCatalog().services(scope.tenantId(), scope.namespace())) {
            List<String> tags = stateStore.getServiceCatalog().instances(scope.service(service)).stream()
                    .flatMap(instance -> instance.tags().stream()).distinct().sorted().toList();
            services.put(service, tags);
        }
        respondJson(exchange, 200, services);
    }

    /** One node's services in the read's scope, as Consul's {@code /v1/catalog/node-services/:node}. */
    private void listNodeServices(HttpExchange exchange) throws IOException {
        if (!prepareCatalogRequest(exchange, "GET")) return;
        String node = pathParameter(exchange, "/v1/catalog/node-services/");
        if (node == null) {
            respondError(exchange, 400, "node_name_required", "Node name is required", false);
            return;
        }
        ReadScope scope = readScope(exchange);
        if (scope == null) return;
        setAppliedIndex(exchange);
        respondJson(exchange, 200, stateStore.getServiceCatalog().instances().stream()
                .filter(instance -> node.equals(instance.nodeId())
                        && scope.tenantId().equals(instance.tenantId())
                        && scope.namespace().equals(instance.namespace()))
                .toList());
    }

    private void listServiceInstances(HttpExchange exchange) throws IOException {
        if (!prepareCatalogRequest(exchange, "GET")) return;
        String prefix = exchange.getHttpContext().getPath() + "/";
        String serviceName = pathParameter(exchange, prefix);
        if (serviceName == null) {
            respondError(exchange, 400, "service_name_required", "Service name is required", false);
            return;
        }
        ReadScope scope = readScope(exchange);
        if (scope == null) return;
        setAppliedIndex(exchange);
        respondJson(exchange, 200, stateStore.getServiceCatalog().instances(scope.service(serviceName)));
    }

    /**
     * Reads the tenant and namespace a discovery read is confined to, from the same optional headers a
     * registration uses. Answers {@code invalid_scope} and returns {@code null} when a header is blank.
     */
    private ReadScope readScope(HttpExchange exchange) throws IOException {
        try {
            return new ReadScope(
                    HeaderRequestContext.optionalScope(exchange, HeaderRequestContext.TENANT_HEADER),
                    HeaderRequestContext.optionalScope(exchange, HeaderRequestContext.NAMESPACE_HEADER));
        } catch (IllegalArgumentException e) {
            respondError(exchange, 400, "invalid_scope", safeMessage(e), false);
            return null;
        }
    }

    private record ReadScope(String tenantId, String namespace) {
        ServiceKey service(String serviceName) {
            return new ServiceKey(tenantId, namespace, serviceName);
        }
    }

    private void observeHealth(HttpExchange exchange) throws IOException {
        if (!prepareCatalogRequest(exchange, "PUT")) return;
        try {
            RequestContext context = new HeaderRequestContext(exchange);
            HealthObservationRequest request = objectMapper.readerFor(HealthObservationRequest.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(exchange.getRequestBody());
            HealthObservation observation = request.toObservation(context);
            RaftCommandResult<?> result = submit(
                    CatalogCommand.observe(observation, clock.instant(), context.nodeUuid()));
            if (refusedAsReserved(exchange, context.nodeId(), result)) return;
            if (result instanceof RaftCommandResult.NotFound<?> notFound) {
                boolean undeclared = QraftStateStore.HEALTH_CHECK_ENTITY.equals(notFound.entityType());
                respondError(exchange, 404, undeclared ? "check_not_declared" : "service_not_found",
                        undeclared ? "The service registration does not declare this check"
                                : "Service instance is not registered", false,
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
        ReadScope scope = readScope(exchange);
        if (scope == null) return;
        setAppliedIndex(exchange);
        Map<ServiceInstanceId, List<HealthCheckState>> checks = stateStore.healthChecks().stream()
                .collect(Collectors.groupingBy(state -> state.checkId().serviceInstanceId()));
        List<HealthServiceEntry> entries = stateStore.getServiceCatalog().instances(scope.service(serviceName))
                .stream()
                .map(instance -> HealthServiceEntry.from(instance,
                        checks.getOrDefault(instance.identity(), List.of()),
                        stateStore.findNode(instance.nodeId()).map(Node::status).orElse(null)))
                .filter(entry -> !passingOnly || entry.passing())
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
        return Deadlines.bound(raftNode.submitCommand(command),
                raftTimeout.toNanos(), TimeUnit.NANOSECONDS)
                .join();
    }

    private static void respondNoContent(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    private void respondJson(HttpExchange exchange, int status, Object body) throws IOException {
        api.json(exchange, status, body);
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
        return api.handle(exchange -> {
            LOG.debug("HTTP request: method={}, path={}", exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath());
            delegate.handle(exchange);
        });
    }

    private void respondError(HttpExchange exchange, int status, String code, String message,
                              boolean retryable) throws IOException {
        respondError(exchange, status, code, message, retryable, Map.of());
    }

    private void respondError(HttpExchange exchange, int status, String code, String message,
                              boolean retryable, Map<String, ?> details) throws IOException {
        api.error(exchange, status, code, message, retryable, details);
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
