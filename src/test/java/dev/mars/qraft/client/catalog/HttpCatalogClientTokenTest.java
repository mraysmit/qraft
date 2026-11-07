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

package dev.mars.qraft.client.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.mars.qraft.client.acl.AclLookup;
import dev.mars.qraft.client.health.CheckObservation;
import dev.mars.qraft.client.health.CheckStatus;
import dev.mars.qraft.common.Node;
import dev.mars.qraft.common.ServiceDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Tests which token each of a client's calls to the servers carries (ACL list, proposal 13, Phase 6): the client's
 * own for its node's registration, renewal and removal, anti-entropy's read, a lookup and every service removal; a
 * service's own for registering it and reporting its checks; the caller's, or none, for a forwarded read, never the
 * client's; and for resolving a token, that token itself. Without tokens, no call carries one.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
class HttpCatalogClientTokenTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ServiceDefinition WEB = new ServiceDefinition("web-1", "web", "10.0.0.1", 80, List.of(),
            Map.of(), true);

    private HttpServer server;
    private HttpCatalogClient client;
    private final Map<String, String> tokens = new LinkedHashMap<>();
    private volatile int resolveStatus = 200;
    private volatile String resolveBody = "{\"token\":{},\"policies\":[],\"roles\":[]}";

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            synchronized (tokens) {
                tokens.put(exchange.getRequestMethod() + " " + path,
                        String.valueOf(exchange.getRequestHeaders().getFirst("X-Qraft-Token")));
            }
            exchange.getRequestBody().readAllBytes();
            int status = 200;
            String body = path.startsWith("/v1/catalog/node-services") || path.startsWith("/v1/catalog/service/")
                    || path.equals("/v1/catalog/nodes") ? "[]"
                    : path.equals("/v1/catalog/check/observe") ? "{\"accepted\":true}"
                    : path.equals("/v1/catalog/register") || path.equals("/v1/catalog/deregister")
                    ? "{\"node\":\"client-1\",\"serviceId\":\"web-1\",\"registered\":true,\"deregistered\":true}"
                    : path.equals("/v1/internal/acl/resolve") ? resolveBody : "{}";
            if (path.equals("/v1/internal/acl/resolve")) status = resolveStatus;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        client = new HttpCatalogClient(HttpClient.newHttpClient(), JSON,
                List.of(URI.create("http://127.0.0.1:" + server.getAddress().getPort())), "client-1",
                "6a1b7c2d-3e4f-4a5b-8c6d-7e8f9a0b1c2d", "default", "default", "dc-1", "eu", Duration.ofSeconds(5),
                new ServerContactTracker(Clock.systemUTC()));
    }

    @AfterEach
    void tearDown() {
        client.close();
        server.stop(0);
    }

    @Test
    void eachCallCarriesTheTokenTheTableNames() {
        client.useTokens(new RequestTokens() {
            @Override public Optional<String> node() { return Optional.of("client-token"); }
            @Override public Optional<String> service(String serviceId) { return Optional.of("token-of-" + serviceId); }
        });

        client.registerNode(Node.of("client-1", "10.0.0.1", "dc-1", "eu", Map.of())).join();
        client.heartbeatNode(1, "registration-1").join();
        client.nodeServices().join();
        client.lookup(WEB).join();
        client.observe(observation()).join();
        client.deregister("web-1").join();
        client.deregisterNode().join();

        assertEquals("client-token", tokens.get("PUT /v1/catalog/register"), "the node's registration");
        assertEquals("client-token", tokens.get("PUT /v1/catalog/node/heartbeat"));
        assertEquals("client-token", tokens.get("GET /v1/catalog/node-services/client-1"));
        assertEquals("client-token", tokens.get("GET /v1/catalog/service/web"));
        assertEquals("token-of-web-1", tokens.get("PUT /v1/catalog/check/observe"));
        assertEquals("client-token", tokens.get("PUT /v1/catalog/deregister"),
                "the last deregistration is the node's; a service removal uses the client's token too");
    }

    @Test
    void aServiceIsRegisteredUnderItsOwnToken() {
        client.useTokens(new RequestTokens() {
            @Override public Optional<String> node() { return Optional.of("client-token"); }
            @Override public Optional<String> service(String serviceId) { return Optional.of("token-of-" + serviceId); }
        });

        client.register(WEB).join();

        assertEquals("token-of-web-1", tokens.get("PUT /v1/catalog/register"));
    }

    @Test
    void aForwardedReadCarriesTheCallersTokenAndNeverTheClients() {
        client.useTokens(new RequestTokens() {
            @Override public Optional<String> node() { return Optional.of("client-token"); }
            @Override public Optional<String> service(String serviceId) { return Optional.empty(); }
        });

        client.forwardRead("/v1/catalog/nodes", "request-1", Map.of("X-Qraft-Token", "caller-token")).join();
        assertEquals("caller-token", tokens.get("GET /v1/catalog/nodes"));
        client.forwardRead("/v1/catalog/nodes", "request-2", Map.of()).join();
        assertEquals("null", tokens.get("GET /v1/catalog/nodes"), "a caller with none is anonymous");
    }

    @Test
    void withoutTokensNoCallCarriesOne() {
        client.registerNode(Node.of("client-1", "10.0.0.1", "dc-1", "eu", Map.of())).join();
        client.register(WEB).join();
        client.observe(observation()).join();

        assertEquals(List.of("null"), tokens.values().stream().distinct().toList());
    }

    @Test
    void resolvingATokenAsksUnderThatTokenAndSaysWhatTheServerAnswered() throws Exception {
        AclLookup found = client.resolveAcl("the-secret").join();
        assertInstanceOf(AclLookup.Found.class, found);
        assertEquals(JSON.readTree(resolveBody), ((AclLookup.Found) found).answer());
        assertEquals("the-secret", tokens.get("POST /v1/internal/acl/resolve"));

        client.resolveAcl(null).join();
        assertEquals("null", tokens.get("POST /v1/internal/acl/resolve"), "the anonymous token is asked for so");

        resolveStatus = 403;
        resolveBody = "{\"code\":\"token_not_found\",\"message\":\"ACL not found\"}";
        assertInstanceOf(AclLookup.NotFound.class, client.resolveAcl("unknown").join());
        resolveStatus = 503;
        resolveBody = "{\"code\":\"leader_unavailable\",\"message\":\"no leader\",\"retryable\":true}";
        assertInstanceOf(AclLookup.Unavailable.class, client.resolveAcl("the-secret").join());
        server.stop(0);
        assertInstanceOf(AclLookup.Unavailable.class, client.resolveAcl("the-secret").join());
    }

    private static CheckObservation observation() {
        return new CheckObservation("web-1", "http", CheckStatus.PASSING, 1, Instant.parse("2026-11-07T10:00:00Z"),
                Duration.ofSeconds(30), true, "", Duration.ZERO);
    }
}
