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
import com.sun.net.httpserver.HttpExchange;
import dev.mars.qraft.client.catalog.ForwardedRead;
import dev.mars.qraft.client.catalog.ServerContactTracker;
import dev.mars.qraft.client.config.ClientConfiguration;
import dev.mars.qraft.common.QraftVersion;
import dev.mars.qraft.common.http.JsonApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
 * pool for a client to know its members from; with no server to ask it fails rather than answer from old data.
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

    /** Forwards a read to the servers; empty when no server could be asked. */
    @FunctionalInterface
    public interface ForwardedReads {
        CompletableFuture<Optional<ForwardedRead>> read(String pathAndQuery, String requestId);
    }

    private final JsonApi api = new JsonApi(new ObjectMapper());
    private final ClientConfiguration config;
    private final String nodeId;
    private final Supplier<ReadinessPolicy.Conditions> readiness;
    private final ServerContactTracker contact;
    private final ForwardedReads reads;
    private final Supplier<CompletableFuture<Boolean>> depart;
    private final Runnable left;

    /**
     * @param depart leaves the catalog, saying whether that was complete
     * @param left   told once the answer to a leave has been written, on the handler's thread
     */
    public LocalClientRoutes(ClientConfiguration config, String nodeId,
                             Supplier<ReadinessPolicy.Conditions> readiness, ServerContactTracker contact,
                             ForwardedReads reads, Supplier<CompletableFuture<Boolean>> depart, Runnable left) {
        this.config = Objects.requireNonNull(config, "config");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.contact = Objects.requireNonNull(contact, "contact");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.depart = Objects.requireNonNull(depart, "depart");
        this.left = Objects.requireNonNull(left, "left");
    }

    /** Serves the routes on {@code localApi}, which refuses their writes once shutdown begins. */
    public void install(LocalApiServer localApi) {
        localApi.route(SELF, this::self);
        localApi.route(MEMBERS, this::members);
        localApi.route(LEAVE, this::leave);
    }

    private void self(HttpExchange exchange) throws IOException {
        if (!exactly(exchange, SELF) || !api.requireMethod(exchange, "GET")) return;
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
        String requestId = exchange.getResponseHeaders().getFirst(JsonApi.REQUEST_ID_HEADER);
        Optional<ForwardedRead> read = reads.read(SERVER_NODES, requestId).join();
        if (read.isEmpty()) {
            api.error(exchange, 503, "no_server_reachable", "No server could be reached", true, Map.of());
            return;
        }
        passOn(exchange, read.get());
    }

    private static void passOn(HttpExchange exchange, ForwardedRead read) throws IOException {
        read.index().ifPresent(index -> exchange.getResponseHeaders().set("X-Qraft-Index", index));
        read.leaderId().ifPresent(leader -> exchange.getResponseHeaders().set("X-Qraft-Leader-Id", leader));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        byte[] body = read.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(read.status(), body.length == 0 ? -1 : body.length);
        try (var output = exchange.getResponseBody()) { output.write(body); }
    }

    private void leave(HttpExchange exchange) throws IOException {
        if (!exactly(exchange, LEAVE) || !api.requireMethod(exchange, "PUT")) return;
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
