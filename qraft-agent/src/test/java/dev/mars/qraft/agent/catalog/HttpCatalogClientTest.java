package dev.mars.qraft.agent.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
