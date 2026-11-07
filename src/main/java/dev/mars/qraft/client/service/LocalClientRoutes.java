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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import dev.mars.qraft.client.acl.ClientAuthorizer;
import dev.mars.qraft.client.acl.TokenKind;
import dev.mars.qraft.client.catalog.ServerContactTracker;
import dev.mars.qraft.client.config.ClientConfiguration;
import dev.mars.qraft.common.QraftVersion;
import dev.mars.qraft.common.http.JsonApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * The local API's information about the client itself, after Consul's agent API:
 * <pre>
 * GET /v1/client/self
 * GET /v1/client/members
 * PUT /v1/client/leave
 * </pre>
 *
 * <p>{@code self} names its settings one by one, so a secret added to the configuration later is not shown unless
 * someone chooses to show it. {@code members} is a forwarded read of the servers' catalog, because Qraft has no gossip
 * pool for a client to know its members from, handled as the discovery reads are ({@link LocalDiscoveryRoutes}).
 *
 * <p>{@code leave} answers once the client has left the catalog, or tried to within its shutdown bound, and only then
 * says the client has left. It cannot wait for the rest of the shutdown, which stops this listener and waits for its
 * handlers, this one included.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-31
 * @version 1.0
 */
public final class LocalClientRoutes {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalClientRoutes.class);
    private static final String SELF = "/v1/client/self";
    private static final String MEMBERS = "/v1/client/members";
    private static final String LEAVE = "/v1/client/leave";
    private static final String SERVER_NODES = "/v1/catalog/nodes";
    private static final String TOKEN = "/v1/client/token/";

    private final JsonApi api = new JsonApi(new ObjectMapper());
    private final ClientConfiguration config;
    private final String nodeId;
    private final Supplier<ReadinessPolicy.Conditions> readiness;
    private final ServerContactTracker contact;
    private final LocalDiscoveryRoutes discovery;
    private final Supplier<CompletableFuture<Boolean>> depart;
    private final Runnable left;
    private final ClientAuthorizer acl;

    /**
     * @param depart leaves the catalog, saying whether that was complete
     * @param left   told once the answer to a leave has been written, on the handler's thread
     */
    public LocalClientRoutes(ClientConfiguration config, String nodeId,
                             Supplier<ReadinessPolicy.Conditions> readiness, ServerContactTracker contact,
                             LocalDiscoveryRoutes.ForwardedReads reads, Supplier<CompletableFuture<Boolean>> depart,
                             Runnable left) {
        this(config, nodeId, readiness, contact, reads, depart, left, ClientAuthorizer.disabled());
    }

    /** @param acl decides each request for the token it presents, and holds the tokens the token route changes */
    public LocalClientRoutes(ClientConfiguration config, String nodeId,
                             Supplier<ReadinessPolicy.Conditions> readiness, ServerContactTracker contact,
                             LocalDiscoveryRoutes.ForwardedReads reads, Supplier<CompletableFuture<Boolean>> depart,
                             Runnable left, ClientAuthorizer acl) {
        this.config = Objects.requireNonNull(config, "config");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.contact = Objects.requireNonNull(contact, "contact");
        this.acl = Objects.requireNonNull(acl, "acl");
        this.discovery = new LocalDiscoveryRoutes(reads, acl);
        this.depart = Objects.requireNonNull(depart, "depart");
        this.left = Objects.requireNonNull(left, "left");
    }

    /** Serves the routes on {@code localApi}, which refuses their writes once shutdown begins. */
    public void install(LocalApiServer localApi) {
        localApi.route(SELF, this::self);
        localApi.route(MEMBERS, this::members);
        localApi.route(LEAVE, this::leave);
        localApi.route(TOKEN, this::setToken);
    }

    /**
     * {@code PUT /v1/client/token/{kind}}, after Consul's {@code agent/token/{kind}}: replaces one of the client's
     * tokens while it runs, given as {@code {"token": "..."}}. It needs {@code client:write} on the node; the token is
     * kept across a restart only with {@code client.acl.enableTokenPersistence}, and never logged or answered.
     */
    private void setToken(HttpExchange exchange) throws IOException {
        String name = exchange.getRequestURI().getPath().substring(TOKEN.length());
        if (!api.requireMethod(exchange, "PUT")) return;
        if (acl.refused(exchange, acl.clientWrite())) return;
        Optional<TokenKind> kind = TokenKind.fromApiName(name);
        if (kind.isEmpty()) {
            api.error(exchange, 400, "invalid_request", "No such token: " + name + "; the tokens are default, client, "
                    + "configFileRegistration and recovery", false, Map.of());
            return;
        }
        JsonNode body;
        try {
            body = new ObjectMapper().readTree(exchange.getRequestBody());
        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
            api.error(exchange, 400, "invalid_request", "The request body is not valid JSON", false, Map.of());
            return;
        }
        JsonNode token = body == null ? null : body.get("token");
        if (token == null || !token.isTextual() || body.size() != 1) {
            api.error(exchange, 400, "invalid_request", "The body gives the token alone, as {\"token\": \"...\"}",
                    false, Map.of());
            return;
        }
        try {
            acl.tokens().set(kind.get(), token.textValue());
        } catch (IllegalArgumentException unusable) {
            api.error(exchange, 400, "invalid_request", unusable.getMessage(), false, Map.of());
            return;
        } catch (IOException failure) {
            LOGGER.error("The {} token could not be kept in the data directory: {}", kind.get().apiName(),
                    failure.toString(), failure);
            api.error(exchange, 500, "token_not_kept", "The token could not be kept in the data directory, so it was "
                    + "not changed", true, Map.of());
            return;
        }
        LOGGER.info("The client's {} token was replaced through the local API", kind.get().apiName());
        api.json(exchange, 200, Map.of("token", kind.get().apiName()));
    }

    private void self(HttpExchange exchange) throws IOException {
        if (!exactly(exchange, SELF) || !api.requireMethod(exchange, "GET")) return;
        if (acl.refused(exchange, acl.clientRead())) return;
        Map<String, Object> self = new LinkedHashMap<>();
        self.put("node", node());
        self.put("version", QraftVersion.current());
        self.put("config", settings());
        self.put("serverContact", serverContact());
        self.put("readiness", readiness());
        api.json(exchange, 200, self);
    }

    private Map<String, Object> node() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("name", config.getNodeName());
        node.put("id", nodeId);
        node.put("address", config.getAddress());
        node.put("datacenter", config.getDatacenter());
        node.put("region", config.getRegion());
        return node;
    }

    private Map<String, Object> settings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("nodeName", config.getNodeName());
        settings.put("dataDirectory", config.getDataDirectory().toString());
        settings.put("address", config.getAddress());
        settings.put("clientPort", config.getClientPort());
        settings.put("bindAddress", config.getBindAddress().getHostAddress());
        settings.put("datacenter", config.getDatacenter());
        settings.put("region", config.getRegion());
        settings.put("tenant", config.getTenant());
        settings.put("namespace", config.getNamespace());
        settings.put("servers", config.getServerUrls().stream().map(URI::toString).toList());
        settings.put("heartbeatIntervalMs", config.getHeartbeatInterval());
        settings.put("shutdownTimeoutMs", config.getShutdownTimeoutMs());
        settings.put("requestTimeoutMs", config.getRequestTimeoutMs());
        settings.put("registrationRetryMinMs", config.getRegistrationRetryMinMs());
        settings.put("registrationRetryMaxMs", config.getRegistrationRetryMaxMs());
        settings.put("contactFreshnessMs", config.getContactFreshnessMs());
        settings.put("loggingDirectory", config.getLoggingDirectory());
        return settings;
    }

    private Map<String, Object> serverContact() {
        Instant last = contact.lastSuccessfulContact();
        Map<String, Object> serverContact = new LinkedHashMap<>();
        serverContact.put("lastSuccessfulContact", last == null ? null : last.toString());
        return serverContact;
    }

    private Map<String, Object> readiness() {
        ReadinessPolicy.Conditions conditions = readiness.get();
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("ready", conditions.ready());
        answer.put("running", conditions.running());
        answer.put("nodeRegistered", conditions.nodeRegistered());
        answer.put("servicesConverged", conditions.servicesConverged());
        answer.put("requiredChecksSatisfied", conditions.requiredChecksSatisfied());
        answer.put("serverContactFresh", conditions.serverContactFresh());
        return answer;
    }

    private void members(HttpExchange exchange) throws IOException {
        if (!exactly(exchange, MEMBERS) || !api.requireMethod(exchange, "GET")) return;
        discovery.forward(exchange, SERVER_NODES);
    }

    private void leave(HttpExchange exchange) throws IOException {
        if (!exactly(exchange, LEAVE) || !api.requireMethod(exchange, "PUT")) return;
        if (acl.refused(exchange, acl.clientWrite())) return;
        LOGGER.info("Leaving at the request of the local API");
        boolean complete = depart.get().join();
        try {
            api.json(exchange, 200, Map.of("deregistered", complete));
        } finally {
            // The caller may have gone, but the client has left all the same.
            left.run();
        }
    }

    /** Whether the path is {@code route} itself; a context also matches what continues it, which is not found. */
    private boolean exactly(HttpExchange exchange, String route) throws IOException {
        if (route.equals(exchange.getRequestURI().getPath())) return true;
        api.error(exchange, 404, "not_found", "No such route: " + exchange.getRequestURI().getPath(), false, Map.of());
        return false;
    }
}
