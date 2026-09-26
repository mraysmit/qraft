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

package dev.mars.qraft.agent.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.mars.qraft.agent.health.CheckObservation;
import dev.mars.qraft.agent.health.CheckStatus;
import dev.mars.qraft.agent.health.ObservationOutcome;
import dev.mars.qraft.catalog.ServiceDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link HttpCatalogClient} request format, outcome classification, seed rotation, lookup,
 * timeouts, and close behaviour against a local HTTP server.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-24
 * @version 1.0
 */
class HttpCatalogClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final List<HttpServer> servers = new ArrayList<>();
    private final List<HttpCatalogClient> clients = new ArrayList<>();

    @AfterEach
    void closeResources() {
        clients.forEach(HttpCatalogClient::close);
        servers.forEach(server -> server.stop(0));
    }

    @Test
    void registerSendsExactSchemaIdentityHeadersAndUniqueRequestIds() throws Exception {
        List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
        URI endpoint = start(exchange -> {
            requests.add(capture(exchange));
            respond(exchange, 200, "{\"serviceId\":\"payments-1\",\"serviceName\":\"payments\","
                    + "\"nodeId\":\"node-a\",\"tenantId\":\"tenant-a\","
                    + "\"namespace\":\"prod\",\"registered\":true}");
        });
        HttpCatalogClient client = client(Duration.ofSeconds(1));
        ServiceDefinition service = service();

        assertInstanceOf(CatalogOutcome.Success.class, client.register(endpoint, service).get(2, TimeUnit.SECONDS));
        assertInstanceOf(CatalogOutcome.Success.class, client.register(endpoint, service).get(2, TimeUnit.SECONDS));

        assertEquals(2, requests.size());
        CapturedRequest first = requests.getFirst();
        assertEquals("PUT", first.method());
        assertEquals("/v1/agent/service/register", first.path());
        assertEquals("node-a", first.header("X-Qraft-Node"));
        assertEquals("tenant-a", first.header("X-Qraft-Tenant"));
        assertEquals("prod", first.header("X-Qraft-Namespace"));
        JsonNode body = JSON.readTree(first.body());
        assertEquals(9, body.size());
        assertEquals("payments-1", body.path("serviceId").textValue());
        assertEquals("payments", body.path("serviceName").textValue());
        assertEquals("127.0.0.1", body.path("address").textValue());
        assertEquals(9090, body.path("port").intValue());
        assertEquals(List.of("blue"), JSON.convertValue(body.path("tags"), List.class));
        assertEquals(Map.of("team", "platform"), JSON.convertValue(body.path("metadata"), Map.class));
        assertEquals("dc-1", body.path("datacenter").textValue());
        assertEquals("eu-west", body.path("region").textValue());
        assertTrue(body.path("enabled").booleanValue());
        assertFalse(body.has("health"));
        assertFalse(first.header("X-Request-Id").isBlank());
        assertFalse(first.header("X-Request-Id").equals(requests.get(1).header("X-Request-Id")));
    }

    @Test
    void deregisterTreatsAlreadyAbsentAsSuccess() throws Exception {
        List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
        URI endpoint = start(exchange -> {
            requests.add(capture(exchange));
            respond(exchange, 200, "{\"serviceId\":\"payments-1\",\"deregistered\":false}");
        });
        HttpCatalogClient client = client(Duration.ofSeconds(1));

        CatalogOutcome.Success outcome = assertInstanceOf(CatalogOutcome.Success.class,
                client.deregister(endpoint, "payments-1").get(2, TimeUnit.SECONDS));
        assertFalse(outcome.changed());
        assertEquals("/v1/agent/service/deregister/payments-1", requests.getFirst().path());
        assertEquals("node-a", requests.getFirst().header("X-Qraft-Node"));
    }

    @Test
    void classifiesEnvelopeAndStatusOutcomesAndExposesLeaderHint() throws Exception {
        URI endpoint = start(exchange -> {
            String id = exchange.getRequestURI().getPath().replaceFirst(".*/", "");
            switch (id) {
                case "retry-envelope" -> respond(exchange, 400,
                        "{\"code\":\"draining\",\"message\":\"later\",\"retryable\":true,"
                                + "\"leaderId\":\"node-b\"}");
                case "reject-envelope" -> respond(exchange, 503,
                        "{\"code\":\"invalid_registration\",\"message\":\"bad\","
                                + "\"retryable\":false}");
                case "malformed-server" -> respond(exchange, 500, "not-json");
                case "malformed-client" -> respond(exchange, 400, "not-json");
                case "rate-limited" -> respond(exchange, 429, "not-json");
                case "outcome-unknown" -> respond(exchange, 503,
                        "{\"code\":\"outcome_unknown\",\"message\":\"response lost\","
                                + "\"retryable\":true}");
                case "bad-gateway" -> respond(exchange, 502, "not-json");
                case "unavailable" -> respond(exchange, 503, "not-json");
                case "bad-success" -> respond(exchange, 200, "{\"serviceId\":\"bad-success\"}");
                default -> respond(exchange, 504, "not-json");
            }
        });
        HttpCatalogClient client = client(Duration.ofSeconds(1));

        CatalogOutcome.Retryable retry = assertInstanceOf(CatalogOutcome.Retryable.class,
                client.deregister(endpoint, "retry-envelope").get(2, TimeUnit.SECONDS));
        assertEquals("draining", retry.code());
        assertEquals("later", retry.message());
        assertEquals("node-b", retry.leaderId());
        CatalogOutcome.Rejected rejected = assertInstanceOf(CatalogOutcome.Rejected.class,
                client.deregister(endpoint, "reject-envelope").get(2, TimeUnit.SECONDS));
        assertEquals("invalid_registration", rejected.code());
        assertInstanceOf(CatalogOutcome.Retryable.class,
                client.deregister(endpoint, "malformed-server").get(2, TimeUnit.SECONDS));
        assertInstanceOf(CatalogOutcome.Rejected.class,
                client.deregister(endpoint, "malformed-client").get(2, TimeUnit.SECONDS));
        assertInstanceOf(CatalogOutcome.Retryable.class,
                client.deregister(endpoint, "rate-limited").get(2, TimeUnit.SECONDS));
        CatalogOutcome.Retryable unknown = assertInstanceOf(CatalogOutcome.Retryable.class,
                client.deregister(endpoint, "outcome-unknown").get(2, TimeUnit.SECONDS));
        assertEquals("outcome_unknown", unknown.code());
        assertInstanceOf(CatalogOutcome.Retryable.class,
                client.deregister(endpoint, "bad-gateway").get(2, TimeUnit.SECONDS));
        assertInstanceOf(CatalogOutcome.Retryable.class,
                client.deregister(endpoint, "unavailable").get(2, TimeUnit.SECONDS));
        assertInstanceOf(CatalogOutcome.Retryable.class,
                client.deregister(endpoint, "gateway-timeout").get(2, TimeUnit.SECONDS));
        CatalogOutcome.Retryable malformedSuccess = assertInstanceOf(CatalogOutcome.Retryable.class,
                client.deregister(endpoint, "bad-success").get(2, TimeUnit.SECONDS));
        assertEquals("invalid_response", malformedSuccess.code());
    }

    @Test
    void timeoutAndConnectionRefusalAreRetryableWithinTheBound() throws Exception {
        URI slow = start(exchange -> {
            try {
                Thread.sleep(500);
                respond(exchange, 200, "{\"serviceId\":\"slow\",\"deregistered\":true}");
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        HttpCatalogClient client = client(Duration.ofMillis(50));
        long started = System.nanoTime();
        CatalogOutcome.Retryable timedOut = assertInstanceOf(CatalogOutcome.Retryable.class,
                client.deregister(slow, "slow").get(2, TimeUnit.SECONDS));
        assertEquals("request_timeout", timedOut.code());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(1)) < 0);

        URI refused;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            refused = URI.create("http://127.0.0.1:" + socket.getLocalPort());
        }
        assertInstanceOf(CatalogOutcome.Retryable.class,
                client.deregister(refused, "offline").get(2, TimeUnit.SECONDS));
    }

    @Test
    void closesOwnedHttpClientExactlyOnceAndRejectsLaterCalls() {
        CloseTrackingHttpClient transport = new CloseTrackingHttpClient(HttpClient.newHttpClient());
        HttpCatalogClient client = new HttpCatalogClient(transport, JSON, "node-a", "tenant-a", "prod",
                "dc-1", "eu-west", Duration.ofSeconds(1));
        clients.add(client);

        client.close();
        client.close();

        assertEquals(1, transport.closeCount.get());
        assertThrows(IllegalStateException.class,
                () -> client.deregister(URI.create("http://127.0.0.1:1"), "closed"));
    }

    @Test
    void rotatesRetryableSeedsAndPrefersTheEndpointThatSucceeded() throws Exception {
        URI refused;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            refused = URI.create("http://127.0.0.1:" + socket.getLocalPort());
        }
        AtomicInteger retryableAttempts = new AtomicInteger();
        URI retryable = start(exchange -> {
            retryableAttempts.incrementAndGet();
            respond(exchange, 503, "{\"code\":\"outcome_unknown\","
                    + "\"message\":\"response lost\",\"retryable\":true}");
        });
        AtomicInteger successfulAttempts = new AtomicInteger();
        URI successful = start(exchange -> {
            successfulAttempts.incrementAndGet();
            respond(exchange, 200, "{\"serviceId\":\"payments-1\",\"serviceName\":\"payments\","
                    + "\"nodeId\":\"node-a\",\"tenantId\":\"tenant-a\","
                    + "\"namespace\":\"prod\",\"registered\":true}");
        });
        HttpCatalogClient client = selectedClient(List.of(refused, retryable, successful));

        assertInstanceOf(CatalogOutcome.Success.class, client.register(service()).get(2, TimeUnit.SECONDS));
        assertEquals(1, retryableAttempts.get());
        assertEquals(1, successfulAttempts.get());

        assertInstanceOf(CatalogOutcome.Success.class, client.register(service()).get(2, TimeUnit.SECONDS));
        assertEquals(1, retryableAttempts.get(), "the preferred successful seed must be tried first");
        assertEquals(2, successfulAttempts.get());
    }

    @Test
    void rejectedOutcomeStopsTheSeedCycle() throws Exception {
        AtomicInteger rejectedAttempts = new AtomicInteger();
        URI rejected = start(exchange -> {
            rejectedAttempts.incrementAndGet();
            respond(exchange, 400, "{\"code\":\"invalid_registration\","
                    + "\"message\":\"bad request\",\"retryable\":false}");
        });
        AtomicInteger laterAttempts = new AtomicInteger();
        URI later = start(exchange -> {
            laterAttempts.incrementAndGet();
            respond(exchange, 200, "{\"serviceId\":\"payments-1\",\"registered\":true}");
        });
        HttpCatalogClient client = selectedClient(List.of(rejected, later));

        assertInstanceOf(CatalogOutcome.Rejected.class, client.register(service()).get(2, TimeUnit.SECONDS));
        assertEquals(1, rejectedAttempts.get());
        assertEquals(0, laterAttempts.get());
    }

    @Test
    void lookupMatchesTheCompleteScopedIdentityAndClassifiesAbsence() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        URI endpoint = start(exchange -> {
            attempts.incrementAndGet();
            assertEquals("GET", exchange.getRequestMethod());
            assertEquals("/v1/catalog/service/payments", exchange.getRequestURI().getPath());
            respond(exchange, 200, "[{\"serviceId\":\"payments-1\","
                    + "\"serviceName\":\"payments\",\"nodeId\":\"other-node\","
                    + "\"tenantId\":\"tenant-a\",\"namespace\":\"prod\"}]");
        });
        HttpCatalogClient client = selectedClient(List.of(endpoint));

        assertInstanceOf(CatalogLookupOutcome.Absent.class,
                client.lookup(service()).get(2, TimeUnit.SECONDS));

        servers.getFirst().removeContext("/");
        servers.getFirst().createContext("/", exchange -> respond(exchange, 200,
                "[{\"serviceId\":\"payments-1\",\"serviceName\":\"payments\","
                        + "\"nodeId\":\"node-a\",\"tenantId\":\"tenant-a\","
                        + "\"namespace\":\"prod\"}]"));
        assertInstanceOf(CatalogLookupOutcome.Present.class,
                client.lookup(service()).get(2, TimeUnit.SECONDS));
        assertEquals(1, attempts.get());
    }

    @Test
    void observeSendsTheObservationSchemaWithScopedIdentity() throws Exception {
        List<CapturedRequest> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        URI endpoint = start(exchange -> {
            requests.add(capture(exchange));
            respond(exchange, 200, "{\"serviceId\":\"payments-1\",\"checkId\":\"http\",\"nodeId\":\"node-a\","
                    + "\"tenantId\":\"tenant-a\",\"namespace\":\"prod\",\"sequenceNumber\":7,"
                    + "\"status\":\"WARNING\",\"deadline\":\"2026-09-26T10:00:30Z\",\"accepted\":true}");
        });
        HttpCatalogClient client = selectedClient(List.of(endpoint));

        ObservationOutcome outcome = client.observe(observation(7)).get(2, TimeUnit.SECONDS);

        assertEquals(new ObservationOutcome.Accepted(7, Instant.parse("2026-09-26T10:00:30Z")), outcome);
        CapturedRequest request = requests.getFirst();
        assertEquals("PUT", request.method());
        assertEquals("/v1/agent/check/observe", request.path());
        assertEquals("node-a", request.header("X-Qraft-Node"));
        assertEquals("tenant-a", request.header("X-Qraft-Tenant"));
        assertEquals("prod", request.header("X-Qraft-Namespace"));
        assertEquals("application/json", request.header("Content-Type"));
        assertEquals(JSON.readTree("""
                {"serviceId":"payments-1","checkId":"http","status":"warning","sequenceNumber":7,
                 "observedAt":"2026-09-26T09:59:59.500Z","ttlMillis":30000,"required":false,
                 "output":"HTTP 429"}
                """), JSON.readTree(request.body()));
    }

    @Test
    void classifiesStaleMissingRejectedRetryableAndMalformedObservationResponses() throws Exception {
        AtomicReference<String[]> reply = new AtomicReference<>();
        URI endpoint = start(exchange -> respond(exchange, Integer.parseInt(reply.get()[0]), reply.get()[1]));
        HttpCatalogClient client = selectedClient(List.of(endpoint));

        reply.set(new String[] {"409", "{\"code\":\"stale_observation\",\"message\":\"older\","
                + "\"retryable\":false,\"currentSequenceNumber\":9}"});
        assertEquals(new ObservationOutcome.Stale(9), client.observe(observation(7)).get(2, TimeUnit.SECONDS));

        reply.set(new String[] {"404", "{\"code\":\"service_not_found\",\"message\":\"missing\","
                + "\"retryable\":false}"});
        assertEquals(new ObservationOutcome.Rejected("service_not_found", "missing", null),
                client.observe(observation(7)).get(2, TimeUnit.SECONDS));

        reply.set(new String[] {"503", "{\"code\":\"leader_unavailable\",\"message\":\"no leader\","
                + "\"retryable\":true,\"leaderId\":\"node-2\"}"});
        assertEquals(new ObservationOutcome.Retryable("leader_unavailable", "no leader", "node-2"),
                client.observe(observation(7)).get(2, TimeUnit.SECONDS));

        reply.set(new String[] {"409", "{\"code\":\"stale_observation\",\"message\":\"older\","
                + "\"retryable\":false}"});
        assertInstanceOf(ObservationOutcome.Rejected.class, client.observe(observation(7)).get(2, TimeUnit.SECONDS));

        reply.set(new String[] {"200", "{\"accepted\":true}"});
        ObservationOutcome malformed = client.observe(observation(7)).get(2, TimeUnit.SECONDS);
        assertEquals("invalid_response", ((ObservationOutcome.Retryable) malformed).code());
    }

    @Test
    void observeRotatesRetryableSeedsAndAStaleAnswerEndsTheCycle() throws Exception {
        URI refused;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            refused = URI.create("http://127.0.0.1:" + socket.getLocalPort());
        }
        AtomicInteger staleAttempts = new AtomicInteger();
        URI stale = start(exchange -> {
            staleAttempts.incrementAndGet();
            respond(exchange, 409, "{\"code\":\"stale_observation\",\"message\":\"older\","
                    + "\"retryable\":false,\"currentSequenceNumber\":12}");
        });
        AtomicInteger laterAttempts = new AtomicInteger();
        URI later = start(exchange -> {
            laterAttempts.incrementAndGet();
            respond(exchange, 500, "{}");
        });
        ControllerContactTracker contact = new ControllerContactTracker(java.time.Clock.systemUTC());
        HttpCatalogClient client = new HttpCatalogClient(HttpClient.newHttpClient(), JSON,
                List.of(refused, stale, later), "node-a", "tenant-a", "prod", "dc-1", "eu-west",
                Duration.ofSeconds(1), contact);
        clients.add(client);

        assertEquals(new ObservationOutcome.Stale(12), client.observe(observation(7)).get(2, TimeUnit.SECONDS));
        assertEquals(1, staleAttempts.get());
        assertEquals(0, laterAttempts.get());
        assertNotNull(contact.lastSuccessfulContact(), "a stale answer is still a successful controller contact");

        client.observe(observation(13)).get(2, TimeUnit.SECONDS);
        assertEquals(2, staleAttempts.get(), "the endpoint that answered is preferred next time");
    }

    private static CheckObservation observation(long sequenceNumber) {
        return new CheckObservation("payments-1", "http", CheckStatus.WARNING, sequenceNumber,
                Instant.parse("2026-09-26T09:59:59.500Z"), Duration.ofSeconds(30), false, "HTTP 429");
    }

    private HttpCatalogClient client(Duration timeout) {
        HttpCatalogClient client = new HttpCatalogClient(HttpClient.newHttpClient(), JSON,
                "node-a", "tenant-a", "prod", "dc-1", "eu-west", timeout);
        clients.add(client);
        return client;
    }

    private HttpCatalogClient selectedClient(List<URI> endpoints) {
        HttpCatalogClient client = new HttpCatalogClient(HttpClient.newHttpClient(), JSON, endpoints,
                "node-a", "tenant-a", "prod", "dc-1", "eu-west", Duration.ofSeconds(1));
        clients.add(client);
        return client;
    }

    private URI start(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        servers.add(server);
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static ServiceDefinition service() {
        return new ServiceDefinition("payments-1", "payments", "127.0.0.1", 9090,
                List.of("blue"), Map.of("team", "platform"), true);
    }

    private static CapturedRequest capture(HttpExchange exchange) throws IOException {
        return new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders(), new String(exchange.getRequestBody().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private record CapturedRequest(String method, String path, com.sun.net.httpserver.Headers headers, String body) {
        String header(String name) { return headers.getFirst(name); }
    }

    private static final class CloseTrackingHttpClient extends HttpClient {
        private final HttpClient delegate;
        private final AtomicInteger closeCount = new AtomicInteger();

        private CloseTrackingHttpClient(HttpClient delegate) { this.delegate = delegate; }
        @Override public Optional<CookieHandler> cookieHandler() { return delegate.cookieHandler(); }
        @Override public Optional<Duration> connectTimeout() { return delegate.connectTimeout(); }
        @Override public Redirect followRedirects() { return delegate.followRedirects(); }
        @Override public Optional<ProxySelector> proxy() { return delegate.proxy(); }
        @Override public SSLContext sslContext() { return delegate.sslContext(); }
        @Override public SSLParameters sslParameters() { return delegate.sslParameters(); }
        @Override public Optional<Authenticator> authenticator() { return delegate.authenticator(); }
        @Override public Version version() { return delegate.version(); }
        @Override public Optional<Executor> executor() { return delegate.executor(); }
        @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                throws IOException, InterruptedException { return delegate.send(request, handler); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            return delegate.sendAsync(request, handler);
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> pushHandler) {
            return delegate.sendAsync(request, handler, pushHandler);
        }
        @Override public void close() { closeCount.incrementAndGet(); delegate.close(); }
    }
}
