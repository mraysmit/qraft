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
import dev.mars.qraft.common.acl.AclAccess;
import dev.mars.qraft.common.acl.AclRequest;
import dev.mars.qraft.common.acl.AclResource;
import dev.mars.qraft.common.http.JsonApi;
import dev.mars.qraft.server.acl.AclPrincipal;
import dev.mars.qraft.server.acl.AclResolver;
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
import dev.mars.qraft.state.AclCommand;
import dev.mars.qraft.state.acl.AclBuiltins;
import dev.mars.qraft.state.acl.AclRefusal;
import dev.mars.qraft.state.acl.AclSecrets;
import dev.mars.qraft.state.acl.AclToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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
    final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    /** The request handling this API shares with the client's local API: request IDs, body limits, errors. */
    private final JsonApi api = new JsonApi(objectMapper);
    private final RaftNode raftNode;
    final QraftStateStore stateStore;
    private final AtomicBoolean draining = new AtomicBoolean();
    final Clock clock;
    private final Duration raftTimeout;
    private final MembershipService membership;
    final AclHttpSettings acl;
    /** Resolves a request's token against this server's applied ACL state; unrestricted with ACLs disabled. */
    private final AclResolver resolver;

    /** How long a request waits for the Raft node to answer a status read or commit a write. */
    public static final Duration DEFAULT_RAFT_TIMEOUT = Duration.ofSeconds(5);
    /** Set on a read whose answer leaves out what the token may not see, as Consul's header is. */
    static final String RESULTS_FILTERED_HEADER = "X-Qraft-Results-Filtered-By-ACLs";

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
        this(port, raftNode, stateStore, clock, ui, assets, raftTimeout, membership, AclHttpSettings.disabled());
    }

    /**
     * @param acl whether ACLs are enabled, the data directory that may hold a bootstrap reset index, and the default
     *            policy. With ACLs enabled, every route but health, the bootstrap and the administrative interface's
     *            files authorizes its request's token before it reads or proposes anything (ACL list, Phase 4)
     */
    public HttpApiServer(int port, RaftNode raftNode, QraftStateStore stateStore, Clock clock,
                         AdminUiConfig ui, UiAssets assets, Duration raftTimeout, MembershipService membership,
                         AclHttpSettings acl) throws IOException {
        this.acl = Objects.requireNonNull(acl, "acl");
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
        // Without a state store every route that takes a token answers that the catalog is unavailable.
        this.resolver = acl.enabled() && stateStore != null
                ? AclResolver.enabled(acl.defaultPolicy(), stateStore.getAcl(), clock) : AclResolver.disabled();
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
        server.createContext("/v1/acl/bootstrap", requestAware(this::bootstrapAcl));
        AclApiRoutes aclApi = new AclApiRoutes(this);
        AclApiRoutes.CONTEXTS.forEach(context -> server.createContext(context, requestAware(aclApi::handle)));
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

    /** This server's Raft state. With ACLs enabled it needs {@code operator:read} (ACL list, proposal 11). */
    private void raftStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respondError(exchange, 405, "method_not_allowed", "Method not allowed", false);
            return;
        }
        AclPrincipal principal = principal(exchange);
        if (principal == null || refused(exchange, principal, OPERATOR_READ)) return;
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
     * whether it is the leader this server knows. With ACLs enabled it needs {@code operator:read}, as Consul's
     * does; with them disabled it is open, like {@code /raft/status}.
     */
    private void raftConfiguration(HttpExchange exchange) throws IOException {
        if (!prepareStateRequest(exchange, "GET")) return;
        AclPrincipal principal = principal(exchange);
        if (principal == null || refused(exchange, principal, OPERATOR_READ)) return;
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
     * With ACLs disabled it needs the operator token; with them enabled, a token with {@code operator:write}, checked
     * here and again by the membership service, on this server and on the leader. The token is sent as
     * {@code X-Qraft-Token} or {@code Authorization: Bearer}. Any server forwards the removal to the leader.
     */
    private void removeRaftPeer(HttpExchange exchange) throws IOException {
        if (!prepareStateRequest(exchange, "DELETE")) return;
        if (membership == null) {
            respondError(exchange, 503, "membership_unavailable", "This server does not serve membership changes",
                    false);
            return;
        }
        AclPrincipal principal = principal(exchange);
        if (principal == null || refused(exchange, principal, OPERATOR_WRITE)) return;
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
                .setToken(presentedToken(exchange)).build();
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

    /**
     * The token a request presents, from {@code X-Qraft-Token} or else a bearer {@code Authorization} header, as
     * Consul reads {@code X-Consul-Token} before {@code Authorization}; empty without one. Qraft reads no token from
     * the query string, which reaches logs (ACL list, section 4).
     */
    private static String presentedToken(HttpExchange exchange) {
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
     *
     * <p>With ACLs enabled the whole body is authorized first, as Consul's {@code vetRegisterWithACL}:
     * {@code node:write} on the name when the node is created or what is recorded for it changes, and on the old name
     * when its ID is registered under another; {@code service:write} on the service's name in its scope, and on the
     * name of the instance with the same ID that it replaces.
     */
    private void registerInCatalog(HttpExchange exchange) throws IOException {
        if (!prepareNodeRequest(exchange, "PUT")) return;
        AclPrincipal principal = principal(exchange);
        if (principal == null) return;
        try {
            RequestContext context = new TokenRequestContext(exchange, principal);
            CatalogRegistrationRequest request = objectMapper.readerFor(CatalogRegistrationRequest.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(exchange.getRequestBody());
            if (request == null) throw new IllegalArgumentException("The body must be a JSON object");
            request.requireSomethingToRegister();
            Optional<Node> node = request.toNode(context);
            ServiceInstance instance = request.service() == null ? null
                    : request.service().toServiceInstance(context);
            List<String> declaredCheckIds = request.service() == null ? null : request.service().declaredCheckIds();
            if (node.isPresent() && refusedNodeRegistration(exchange, principal, node.get())) return;
            if (instance != null && refusedServiceRegistration(exchange, principal, instance)) return;
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

    /**
     * Answers 403 when {@code principal} may not register {@code proposed}: {@code node:write} on its name when the
     * registration creates the node or changes what is recorded for it, as Consul's {@code ChangesNode} decides, and
     * on any other name its ID is registered under, which the registration takes over by renaming it.
     */
    private boolean refusedNodeRegistration(HttpExchange exchange, AclPrincipal principal, Node proposed)
            throws IOException {
        Node recorded = stateStore.findNode(proposed.name()).orElse(null);
        if (changesNode(recorded, proposed) && refused(exchange, principal, nodeWrite(proposed.name()))) return true;
        if (proposed.id() == null) return false;
        for (Node other : stateStore.getNodes().values()) {
            if (proposed.id().equals(other.id()) && !other.name().equals(proposed.name())
                    && refused(exchange, principal, nodeWrite(other.name()))) {
                return true;
            }
        }
        return false;
    }

    /** Whether registering {@code proposed} creates the node or changes what a client describes of it. */
    private static boolean changesNode(Node recorded, Node proposed) {
        return recorded == null
                || !Objects.equals(recorded.name(), proposed.name())
                || !Objects.equals(recorded.id(), proposed.id())
                || !Objects.equals(recorded.address(), proposed.address())
                || !Objects.equals(recorded.datacenter(), proposed.datacenter())
                || !Objects.equals(recorded.region(), proposed.region())
                || !recorded.metadata().equals(proposed.metadata());
    }

    /**
     * Answers 403 when {@code principal} may not register {@code instance}: {@code service:write} on its name in its
     * scope, and on the name of the instance with the same ID that it replaces, as Consul's
     * {@code vetRegisterWithACL}.
     */
    private boolean refusedServiceRegistration(HttpExchange exchange, AclPrincipal principal, ServiceInstance instance)
            throws IOException {
        if (refused(exchange, principal, serviceWrite(instance))) return true;
        Optional<ServiceInstance> replaced = stateStore.getServiceCatalog().find(instance.identity());
        return replaced.isPresent() && !replaced.get().serviceName().equals(instance.serviceName())
                && refused(exchange, principal, serviceWrite(replaced.get()));
    }

    /**
     * Creates the first management token, after Consul's {@code PUT /v1/acl/bootstrap} (ACL list, proposal 12). It
     * needs no token, and works once: afterwards it answers 403 {@code permission_denied}, naming the reset index. An
     * operator who has lost every management secret writes that index to {@code acl-bootstrap-reset} in the leader's
     * data directory, which allows one more bootstrap and is removed when used. The body may supply the secret as
     * {@code bootstrapSecret}, a UUID outside the reserved range; otherwise one is generated. The secret is returned
     * in this answer only, the server keeps its hash, and neither the body nor the secret is logged. With ACLs
     * disabled it answers 401 {@code acl_disabled}, as Consul's ACL endpoints do.
     */
    /** A management token file is configured and no token in the applied state has its secret's hash. */
    private boolean managementTokenPending() {
        String hash = acl.managementSecretHash();
        return hash != null && stateStore.getAcl().tokens().stream().noneMatch(t -> t.secretHash().equals(hash));
    }

    private void bootstrapAcl(HttpExchange exchange) throws IOException {
        if (!prepareStateRequest(exchange, "PUT")) return;
        if (!acl.enabled()) {
            respondError(exchange, 401, "acl_disabled", "ACL support disabled", false);
            return;
        }
        if (managementTokenPending()) {
            // Checked on the applied state before proposing: once the file's token is in, a bootstrap has been
            // applied, so a later one succeeds only through the reset file.
            respondError(exchange, 503, "management_token_pending", "ACL bootstrap is closed until the management "
                    + "token from server.acl.initialManagementTokenFile is installed", true);
            return;
        }
        try {
            String secret = bootstrapSecret(exchange);
            Path resetFile = acl.dataDirectory().resolve(AclHttpSettings.BOOTSTRAP_RESET_FILE);
            long resetIndex = bootstrapResetIndex(resetFile);
            AclToken token = new AclToken(UUID.randomUUID().toString(), AclSecrets.hash(secret),
                    "Bootstrap Token (Global Management)", List.of(AclBuiltins.GLOBAL_MANAGEMENT_ID), List.of(),
                    List.of(), List.of(), clock.instant(), null, false);
            RaftCommandResult<?> result = submit(AclCommand.bootstrap(token, resetIndex));
            if (result instanceof RaftCommandResult.Refused<?> refused) {
                boolean bootstrapped = refused.code().equals(AclRefusal.ACL_BOOTSTRAPPED.code())
                        || refused.code().equals(AclRefusal.INVALID_RESET_INDEX.code());
                if (bootstrapped) {
                    respondError(exchange, 403, "permission_denied", refused.message(), false,
                            Map.of("resetIndex", stateStore.getAcl().bootstrapIndex()));
                } else {
                    respondError(exchange, 409, refused.code(), refused.message(), false);
                }
                return;
            }
            if (resetIndex > 0) {
                try {
                    Files.deleteIfExists(resetFile);
                } catch (IOException e) {
                    LOG.warn("ACL bootstrap used {} but could not remove it; remove it by hand: {}", resetFile,
                            e.getMessage());
                }
            }
            LOG.info("ACL bootstrap created the management token with accessor {}", token.accessorId());
            setAppliedIndex(exchange);
            respondJson(exchange, 200, AclTokenAnswer.of(token, secret, stateStore.getAcl()));
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            // The original message names the problem without quoting the body, which may hold a secret.
            respondError(exchange, 400, "invalid_bootstrap", e.getOriginalMessage(), false);
        } catch (IllegalArgumentException e) {
            respondError(exchange, 400, "invalid_bootstrap", safeMessage(e), false);
        } catch (CompletionException e) {
            respondUnavailable(exchange, e);
        }
    }

    /** The secret the body supplies as {@code bootstrapSecret}, or a generated one when the body is empty. */
    private String bootstrapSecret(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        if (new String(body, java.nio.charset.StandardCharsets.UTF_8).isBlank()) return UUID.randomUUID().toString();
        AclBootstrapRequest request = objectMapper.readerFor(AclBootstrapRequest.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(body);
        if (request == null) throw new IllegalArgumentException("The body must be a JSON object");
        if (request.bootstrapSecret() == null) return UUID.randomUUID().toString();
        // The message never repeats what was supplied: it may be a secret.
        if (!AclSecrets.isUuid(request.bootstrapSecret())) {
            throw new IllegalArgumentException("bootstrapSecret must be a UUID");
        }
        if (AclSecrets.isReserved(request.bootstrapSecret())) {
            throw new IllegalArgumentException("bootstrapSecret lies in the range of UUIDs kept for built-ins");
        }
        return request.bootstrapSecret();
    }

    /** The reset index in {@code resetFile}, as Consul's {@code fileBootstrapResetIndex} reads it, or 0 without one. */
    private static long bootstrapResetIndex(Path resetFile) throws IOException {
        if (!Files.exists(resetFile)) return 0;
        String text = Files.readString(resetFile).strip();
        try {
            long index = Long.parseLong(text);
            if (index > 0) return index;
        } catch (NumberFormatException ignored) {
            // Refused below with the file's name.
        }
        throw new IllegalArgumentException(AclHttpSettings.BOOTSTRAP_RESET_FILE + " in the data directory must hold the "
                + "reset index, a whole number above zero");
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
     *
     * <p>With ACLs enabled, as Consul's {@code vetDeregisterWithACL}: {@code node:write} on the node allows removing
     * anything on it, so that a client can remove what it no longer wants whoever registered it. Without it, a service
     * needs {@code service:write} on its name, and an unknown service ID is answered 404 {@code service_not_found}, as
     * Consul answers "Unknown service ID"; the node itself needs {@code node:write}.
     */
    private void deregisterFromCatalog(HttpExchange exchange) throws IOException {
        if (!prepareNodeRequest(exchange, "PUT")) return;
        AclPrincipal principal = principal(exchange);
        if (principal == null) return;
        try {
            RequestContext context = new TokenRequestContext(exchange, principal);
            boolean writesNode = principal.allows(nodeWrite(context.nodeId()));
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
                ServiceInstanceId identity = new DeregistrationRequest(serviceId, context).identity();
                if (!writesNode) {
                    Optional<ServiceInstance> existing = stateStore.getServiceCatalog().find(identity);
                    if (existing.isEmpty()) {
                        respondError(exchange, 404, "service_not_found", "Unknown service ID " + serviceId
                                + " for node " + context.nodeId(), false, Map.of("serviceId", serviceId));
                        return;
                    }
                    if (refused(exchange, principal, serviceWrite(existing.get()))) return;
                }
                RaftCommandResult<?> removed = submit(CatalogCommand.deregister(identity, context.nodeUuid()));
                if (refusedAsReserved(exchange, context.nodeId(), removed)) return;
                setAppliedIndex(exchange);
                Map<String, Object> answer = new LinkedHashMap<>();
                answer.put("node", context.nodeId());
                answer.put("serviceId", serviceId);
                answer.put("deregistered", removed instanceof RaftCommandResult.Success<?>);
                respondJson(exchange, 200, answer);
                return;
            }
            if (!writesNode && refused(exchange, principal, nodeWrite(context.nodeId()))) return;
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

    /**
     * Records a heartbeat of the node that {@code X-Qraft-Node} names, which makes the node healthy. With ACLs enabled
     * it needs {@code node:write} on the name; Consul's agents have gossip instead.
     */
    private void heartbeatNode(HttpExchange exchange) throws IOException {
        if (!prepareNodeRequest(exchange, "PUT")) return;
        AclPrincipal principal = principal(exchange);
        if (principal == null) return;
        try {
            RequestContext context = new TokenRequestContext(exchange, principal);
            if (refused(exchange, principal, nodeWrite(context.nodeId()))) return;
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

    /**
     * Lists the registered nodes in name order, after Consul's {@code GET /v1/catalog/nodes}, leaving out those the
     * token may not read ({@code node:read}).
     */
    private void listNodes(HttpExchange exchange) throws IOException {
        if (!prepareNodeRequest(exchange, "GET") || refusedForItsQuery(exchange)) return;
        AclPrincipal principal = principal(exchange);
        if (principal == null) return;
        setAppliedIndex(exchange);
        List<Node> all = List.copyOf(stateStore.getNodes().values());
        List<Node> readable = all.stream().filter(node -> principal.allows(nodeRead(node.name()))).toList();
        markFiltered(exchange, all, readable);
        respondJson(exchange, 200, readable.stream().map(NodeEntry::from).toList());
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

    /** The services in the read's scope with their tags, leaving out those the token may not read. */
    private void listServices(HttpExchange exchange) throws IOException {
        if (!prepareCatalogRequest(exchange, "GET") || refusedForItsQuery(exchange)) return;
        AclPrincipal principal = principal(exchange);
        if (principal == null) return;
        ReadScope scope = readScope(exchange);
        if (scope == null) return;
        setAppliedIndex(exchange);
        List<String> all = List.copyOf(stateStore.getServiceCatalog().services(scope.tenantId(), scope.namespace()));
        List<String> readable = all.stream().filter(service -> principal.allows(
                AclRequest.service(service, scope.tenantId(), scope.namespace(), AclAccess.READ))).toList();
        markFiltered(exchange, all, readable);
        Map<String, List<String>> services = new LinkedHashMap<>();
        for (String service : readable) {
            List<String> tags = stateStore.getServiceCatalog().instances(scope.service(service)).stream()
                    .flatMap(instance -> instance.tags().stream()).distinct().sorted().toList();
            services.put(service, tags);
        }
        respondJson(exchange, 200, services);
    }

    /**
     * One node's services in the read's scope, as Consul's {@code /v1/catalog/node-services/:node}: nothing without
     * {@code node:read} on the node, and otherwise the services the token may read.
     */
    private void listNodeServices(HttpExchange exchange) throws IOException {
        if (!prepareCatalogRequest(exchange, "GET")) return;
        AclPrincipal principal = principal(exchange);
        if (principal == null) return;
        String node = pathParameter(exchange, "/v1/catalog/node-services/");
        if (node == null) {
            respondError(exchange, 400, "node_name_required", "Node name is required", false);
            return;
        }
        if (refusedForItsQuery(exchange)) return;
        ReadScope scope = readScope(exchange);
        if (scope == null) return;
        setAppliedIndex(exchange);
        List<ServiceInstance> all = stateStore.getServiceCatalog().instances().stream()
                .filter(instance -> node.equals(instance.nodeId())
                        && scope.tenantId().equals(instance.tenantId())
                        && scope.namespace().equals(instance.namespace()))
                .toList();
        List<ServiceInstance> readable = principal.allows(nodeRead(node))
                ? all.stream().filter(instance -> principal.allows(serviceRead(instance))).toList() : List.of();
        markFiltered(exchange, all, readable);
        respondJson(exchange, 200, readable);
    }

    /** A service's instances in the read's scope, leaving out those the token may not read. */
    private void listServiceInstances(HttpExchange exchange) throws IOException {
        if (!prepareCatalogRequest(exchange, "GET")) return;
        AclPrincipal principal = principal(exchange);
        if (principal == null) return;
        String prefix = exchange.getHttpContext().getPath() + "/";
        String serviceName = pathParameter(exchange, prefix);
        if (serviceName == null) {
            respondError(exchange, 400, "service_name_required", "Service name is required", false);
            return;
        }
        if (refusedForItsQuery(exchange)) return;
        ReadScope scope = readScope(exchange);
        if (scope == null) return;
        setAppliedIndex(exchange);
        List<ServiceInstance> all = stateStore.getServiceCatalog().instances(scope.service(serviceName));
        List<ServiceInstance> readable = readableInstances(principal, all);
        markFiltered(exchange, all, readable);
        respondJson(exchange, 200, readable);
    }

    /**
     * The instances {@code principal} may read: an instance needs both {@code node:read} on its node and
     * {@code service:read} on its service, as Consul's {@code CheckServiceNode.CanRead}.
     */
    private static List<ServiceInstance> readableInstances(AclPrincipal principal, List<ServiceInstance> instances) {
        return instances.stream().filter(instance -> principal.allows(nodeRead(instance.nodeId()))
                && principal.allows(serviceRead(instance))).toList();
    }

    /**
     * Reads the tenant and namespace a discovery read is confined to, from the same optional headers a
     * registration uses. Answers {@code invalid_scope} and returns {@code null} when a header is blank.
     */
    private ReadScope readScope(HttpExchange exchange) throws IOException {
        try {
            return new ReadScope(
                    TokenRequestContext.optionalScope(exchange, TokenRequestContext.TENANT_HEADER),
                    TokenRequestContext.optionalScope(exchange, TokenRequestContext.NAMESPACE_HEADER));
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

    /**
     * Records a check's observation. With ACLs enabled it needs {@code service:write} on the check's service; an
     * observation of an instance that is not registered is answered 404 as before, since there is no service to name.
     */
    private void observeHealth(HttpExchange exchange) throws IOException {
        if (!prepareCatalogRequest(exchange, "PUT")) return;
        AclPrincipal principal = principal(exchange);
        if (principal == null) return;
        try {
            RequestContext context = new TokenRequestContext(exchange, principal);
            HealthObservationRequest request = objectMapper.readerFor(HealthObservationRequest.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(exchange.getRequestBody());
            HealthObservation observation = request.toObservation(context);
            Optional<ServiceInstance> observed = stateStore.getServiceCatalog()
                    .find(observation.checkId().serviceInstanceId());
            if (observed.isPresent() && refused(exchange, principal, serviceWrite(observed.get()))) return;
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

    /** A service's instances with their checks, leaving out those the token may not read. */
    private void listServiceHealth(HttpExchange exchange) throws IOException {
        if (!prepareCatalogRequest(exchange, "GET")) return;
        AclPrincipal principal = principal(exchange);
        if (principal == null) return;
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
        List<ServiceInstance> all = stateStore.getServiceCatalog().instances(scope.service(serviceName));
        List<ServiceInstance> readable = readableInstances(principal, all);
        markFiltered(exchange, all, readable);
        List<HealthServiceEntry> entries = readable.stream()
                .map(instance -> HealthServiceEntry.from(instance,
                        checks.getOrDefault(instance.identity(), List.of()),
                        stateStore.findNode(instance.nodeId()).map(Node::status).orElse(null)))
                .filter(entry -> !passingOnly || entry.passing())
                .toList();
        respondJson(exchange, 200, entries);
    }

    /**
     * Answers {@code invalid_query} for a read that takes no query parameter and was given one. Ignoring it, as
     * Consul does a parameter it does not know, would answer another question than the one asked.
     */
    boolean refusedForItsQuery(HttpExchange exchange) throws IOException {
        String rawQuery = exchange.getRequestURI().getRawQuery();
        if (rawQuery == null || rawQuery.isEmpty()) return false;
        Optional<String> parameter = Arrays.stream(rawQuery.split("&")).filter(text -> !text.isEmpty()).findFirst();
        if (parameter.isEmpty()) return false;
        respondError(exchange, 400, "invalid_query", "Unsupported query parameter: "
                + URLDecoder.decode(parameter.get().split("=", 2)[0], StandardCharsets.UTF_8), false);
        return true;
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

    void setAppliedIndex(HttpExchange exchange) {
        exchange.getResponseHeaders().set("X-Qraft-Index", Long.toString(stateStore.getLastAppliedIndex()));
    }

    private boolean prepareCatalogRequest(HttpExchange exchange, String expectedMethod) throws IOException {
        return prepareStateRequest(exchange, expectedMethod);
    }

    boolean prepareStateRequest(HttpExchange exchange, String expectedMethod) throws IOException {
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

    RaftCommandResult<?> submit(RaftCommand command) {
        return Deadlines.bound(raftNode.submitCommand(command),
                raftTimeout.toNanos(), TimeUnit.NANOSECONDS)
                .join();
    }

    private static void respondNoContent(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    void respondJson(HttpExchange exchange, int status, Object body) throws IOException {
        api.json(exchange, status, body);
    }

    void respondUnavailable(HttpExchange exchange, CompletionException error) throws IOException {
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

    private static final AclRequest OPERATOR_READ = AclRequest.single(AclResource.OPERATOR, AclAccess.READ);
    private static final AclRequest OPERATOR_WRITE = AclRequest.single(AclResource.OPERATOR, AclAccess.WRITE);

    /**
     * The principal the request acts as, resolved from its token against this server's applied ACL state; or
     * {@code null} after answering 403 {@code token_not_found} for a token that names none, as Consul answers 403 for
     * "ACL not found". The secret is never answered or logged.
     */
    AclPrincipal principal(HttpExchange exchange) throws IOException {
        Optional<AclPrincipal> principal = resolver.resolve(presentedToken(exchange));
        if (principal.isPresent()) return principal.get();
        LOG.info("ACL refused {} {}: the token was not found", exchange.getRequestMethod(),
                exchange.getRequestURI().getPath());
        respondError(exchange, 403, "token_not_found", AclResolver.TOKEN_NOT_FOUND_MESSAGE, false);
        return null;
    }

    /**
     * Answers 403 {@code permission_denied}, in Consul's words, and returns {@code true} when {@code principal} may
     * not make {@code request}. A refusal is not an error: it is logged at INFO with the accessor ID.
     */
    boolean refused(HttpExchange exchange, AclPrincipal principal, AclRequest request) throws IOException {
        if (principal.allows(request)) return false;
        String denied = principal.permissionDenied(request);
        LOG.info("ACL refused {} {}: {}", exchange.getRequestMethod(), exchange.getRequestURI().getPath(), denied);
        respondError(exchange, 403, "permission_denied", denied, false);
        return true;
    }

    /** Marks a read whose answer, {@code kept}, leaves out some of {@code all}, as Consul's results filter does. */
    private static void markFiltered(HttpExchange exchange, List<?> all, List<?> kept) {
        if (kept.size() < all.size()) exchange.getResponseHeaders().set(RESULTS_FILTERED_HEADER, "true");
    }

    private static AclRequest nodeRead(String name) {
        return AclRequest.named(AclResource.NODE, name, AclAccess.READ);
    }

    private static AclRequest nodeWrite(String name) {
        return AclRequest.named(AclResource.NODE, name, AclAccess.WRITE);
    }

    private static AclRequest serviceRead(ServiceInstance instance) {
        return AclRequest.service(instance.serviceName(), instance.tenantId(), instance.namespace(), AclAccess.READ);
    }

    private static AclRequest serviceWrite(ServiceInstance instance) {
        return AclRequest.service(instance.serviceName(), instance.tenantId(), instance.namespace(), AclAccess.WRITE);
    }

    private HttpHandler requestAware(HttpHandler delegate) {
        return api.handle(exchange -> {
            LOG.debug("HTTP request: method={}, path={}", exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath());
            delegate.handle(exchange);
        });
    }

    void respondError(HttpExchange exchange, int status, String code, String message,
                      boolean retryable) throws IOException {
        respondError(exchange, status, code, message, retryable, Map.of());
    }

    void respondError(HttpExchange exchange, int status, String code, String message,
                              boolean retryable, Map<String, ?> details) throws IOException {
        api.error(exchange, status, code, message, retryable, details);
    }

    private static String pathParameter(HttpExchange exchange, String prefix) {
        String path = exchange.getRequestURI().getPath();
        if (!path.startsWith(prefix) || path.length() == prefix.length()) return null;
        // As Consul's strings.TrimPrefix(req.URL.Path, ...): the path is already decoded, and the rest of it is the name.
        String value = path.substring(prefix.length());
        return value.isBlank() ? null : value;
    }

    static String safeMessage(Throwable error) {
        return Objects.toString(error.getMessage(), error.getClass().getSimpleName());
    }
}
