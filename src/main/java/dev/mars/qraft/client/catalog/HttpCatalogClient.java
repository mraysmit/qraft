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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.client.acl.AclLookup;
import dev.mars.qraft.common.Node;
import dev.mars.qraft.client.health.CheckObservation;
import dev.mars.qraft.client.health.ObservationClient;
import dev.mars.qraft.client.health.ObservationOutcome;
import dev.mars.qraft.common.ServiceDefinition;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * JDK HTTP implementation shared by catalog and client control-plane requests.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-24
 * @version 1.0
 */
public final class HttpCatalogClient implements CatalogClient, ObservationClient {
    private static final String LEADER_ID_HEADER = "X-Qraft-Leader-Id";

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(HttpCatalogClient.class);
    private final HttpClient httpClient;
    private final java.util.Set<String> tlsWarnings = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final ObjectMapper objectMapper;
    private final ServerEndpoints endpoints;
    private final String nodeId;
    /** The node's ID, or {@code null} for a client that speaks without one, as protocol fixtures do. */
    private final String nodeUuid;
    private final String tenant;
    private final String namespace;
    private final String datacenter;
    private final String region;
    private final Duration requestTimeout;
    private final ServerContactTracker contactTracker;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile RequestTokens tokens = RequestTokens.NONE;

    /** Single-endpoint constructor retained for protocol fixtures and direct attempts. */
    public HttpCatalogClient(HttpClient httpClient, ObjectMapper objectMapper,
                             String nodeId, String tenant, String namespace,
                             String datacenter, String region, Duration requestTimeout) {
        this(httpClient, objectMapper, (ServerEndpoints) null, nodeId, null, tenant, namespace,
                datacenter, region, requestTimeout, new ServerContactTracker(Clock.systemUTC()));
    }

    /** Production constructor with ordered server-seed selection. */
    public HttpCatalogClient(HttpClient httpClient, ObjectMapper objectMapper, List<URI> servers,
                             String nodeId, String tenant, String namespace,
                             String datacenter, String region, Duration requestTimeout) {
        this(httpClient, objectMapper, new ServerEndpoints(servers), nodeId, null, tenant, namespace,
                datacenter, region, requestTimeout, new ServerContactTracker(Clock.systemUTC()));
    }

    public HttpCatalogClient(HttpClient httpClient, ObjectMapper objectMapper, List<URI> servers,
                             String nodeId, String tenant, String namespace,
                             String datacenter, String region, Duration requestTimeout,
                             ServerContactTracker contactTracker) {
        this(httpClient, objectMapper, new ServerEndpoints(servers), nodeId, null, tenant, namespace,
                datacenter, region, requestTimeout, contactTracker);
    }

    /**
     * Production constructor for a client with a node ID, which every request carries in {@code X-Qraft-Node-ID}
     * beside the node's name, so that the servers can tell this node from another of the same name.
     *
     * @param nodeId   the node's name
     * @param nodeUuid the node's ID, the UUID kept in the client's data directory
     */
    public HttpCatalogClient(HttpClient httpClient, ObjectMapper objectMapper, List<URI> servers,
                             String nodeId, String nodeUuid, String tenant, String namespace,
                             String datacenter, String region, Duration requestTimeout,
                             ServerContactTracker contactTracker) {
        this(httpClient, objectMapper, new ServerEndpoints(servers), nodeId, required("nodeUuid", nodeUuid),
                tenant, namespace, datacenter, region, requestTimeout, contactTracker);
    }

    private HttpCatalogClient(HttpClient httpClient, ObjectMapper objectMapper, ServerEndpoints endpoints,
                              String nodeId, String nodeUuid, String tenant, String namespace,
                              String datacenter, String region, Duration requestTimeout,
                              ServerContactTracker contactTracker) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").findAndRegisterModules();
        this.endpoints = endpoints;
        this.nodeId = required("nodeId", nodeId);
        this.nodeUuid = nodeUuid;
        this.tenant = required("tenant", tenant);
        this.namespace = required("namespace", namespace);
        this.datacenter = Objects.requireNonNullElse(datacenter, "");
        this.region = Objects.requireNonNullElse(region, "");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.contactTracker = Objects.requireNonNull(contactTracker, "contactTracker");
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
    }

    /** Sets the tokens this client's calls carry from now on (ACL list, proposal 13). */
    public void useTokens(RequestTokens tokens) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
    }

    /**
     * Asks the servers what {@code secret} holds, through {@code POST /v1/internal/acl/resolve}, across the seeds;
     * {@code null} asks for the anonymous token.
     */
    public CompletableFuture<AclLookup> resolveAcl(String secret) {
        ensureOpen();
        if (endpoints == null) {
            throw new IllegalStateException("No server seeds were configured for automatic selection");
        }
        return resolveAttempt(endpoints.cycle(), 0, secret, "no server was asked");
    }

    private CompletableFuture<AclLookup> resolveAttempt(List<URI> cycle, int index, String secret, String last) {
        URI endpoint = cycle.get(index);
        HttpRequest request = withToken(request(endpoint, "/v1/internal/acl/resolve"), Optional.ofNullable(secret))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        CompletableFuture<AclLookup> answer;
        try {
            answer = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .handle((response, failure) -> failure == null ? classifyResolution(response)
                            : new AclLookup.Unavailable(transportCode(request, failure) + ": " + unwrap(failure)));
        } catch (RuntimeException failure) {
            answer = CompletableFuture.completedFuture(new AclLookup.Unavailable(transportCode(request, failure)));
        }
        return answer.thenCompose(lookup -> {
            if (!(lookup instanceof AclLookup.Unavailable unavailable)) {
                endpoints.markSuccessful(endpoint);
                contactTracker.recordSuccessfulContact();
                return CompletableFuture.completedFuture(lookup);
            }
            return index + 1 == cycle.size() ? CompletableFuture.completedFuture(lookup)
                    : resolveAttempt(cycle, index + 1, secret, unavailable.reason());
        });
    }

    /** A token's resolution, or that no token has the secret; anything else is no answer, for the down policy. */
    private AclLookup classifyResolution(HttpResponse<String> response) {
        if (isSuccess(response.statusCode())) {
            try {
                JsonNode answer = objectMapper.readTree(response.body());
                if (answer != null && answer.path("token").isObject()) return new AclLookup.Found(answer);
            } catch (IOException malformed) {
                // Answered below as no answer.
            }
            return new AclLookup.Unavailable("the server answered a resolution that cannot be read");
        }
        JsonNode error = parseObject(response.body());
        String code = error == null ? "" : error.path("code").asText();
        if (response.statusCode() == 403 && "token_not_found".equals(code)) return new AclLookup.NotFound();
        return new AclLookup.Unavailable("HTTP " + response.statusCode() + (code.isEmpty() ? "" : " " + code));
    }

    /** {@code request} carrying {@code token}, if there is one. */
    private static HttpRequest.Builder withToken(HttpRequest.Builder request, Optional<String> token) {
        token.ifPresent(secret -> request.header("X-Qraft-Token", secret));
        return request;
    }

    @Override
    public CompletableFuture<CatalogOutcome> register(ServiceDefinition service) {
        return acrossSeeds(endpoint -> register(endpoint, service));
    }

    @Override
    public CompletableFuture<CatalogOutcome> deregister(String serviceId) {
        return acrossSeeds(endpoint -> deregister(endpoint, serviceId));
    }

    public CompletableFuture<CatalogOutcome> register(URI server, ServiceDefinition service) {
        ensureOpen();
        Objects.requireNonNull(service, "service");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serviceId", service.id());
        body.put("serviceName", service.name());
        body.put("address", service.address());
        body.put("port", service.port());
        body.put("tags", service.tags());
        body.put("metadata", service.metadata());
        body.put("datacenter", datacenter);
        body.put("region", region);
        body.put("enabled", service.enabled());
        body.put("checks", service.checkIds());
        try {
            HttpRequest request = withToken(request(server, "/v1/catalog/register"), tokens.service(service.id()))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(Map.of("service", body))))
                    .build();
            return send(request, response -> classifyCatalog(response, CatalogOperation.REGISTER));
        } catch (IOException error) {
            return CompletableFuture.completedFuture(retryable("request_encoding", error));
        }
    }

    public CompletableFuture<CatalogOutcome> deregister(URI server, String serviceId) {
        ensureOpen();
        String id = required("serviceId", serviceId);
        try {
            HttpRequest request = withToken(request(server, "/v1/catalog/deregister"), tokens.node())
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(Map.of("serviceId", id))))
                    .build();
            return send(request, response -> classifyCatalog(response, CatalogOperation.DEREGISTER));
        } catch (IOException error) {
            return CompletableFuture.completedFuture(retryable("request_encoding", error));
        }
    }

    @Override
    public CompletableFuture<CatalogLookupOutcome> lookup(ServiceDefinition service) {
        ensureOpen();
        Objects.requireNonNull(service, "service");
        if (endpoints == null) {
            throw new IllegalStateException("No server seeds were configured for automatic selection");
        }
        List<URI> cycle = endpoints.cycle();
        return lookupAttempt(cycle, 0, service);
    }

    private CompletableFuture<CatalogLookupOutcome> lookupAttempt(
            List<URI> cycle, int index, ServiceDefinition service) {
        URI endpoint = cycle.get(index);
        HttpRequest request = withToken(request(endpoint, "/v1/catalog/service/" + encode(service.name())),
                tokens.node()).GET().build();
        return sendLookup(request, service).thenCompose(outcome -> {
            if (outcome instanceof CatalogLookupOutcome.Present
                    || outcome instanceof CatalogLookupOutcome.Absent) {
                endpoints.markSuccessful(endpoint);
                contactTracker.recordSuccessfulContact();
                return CompletableFuture.completedFuture(outcome);
            }
            if (outcome instanceof CatalogLookupOutcome.Rejected || index + 1 == cycle.size()) {
                return CompletableFuture.completedFuture(outcome);
            }
            return lookupAttempt(cycle, index + 1, service);
        });
    }

    private CompletableFuture<CatalogLookupOutcome> sendLookup(
            HttpRequest request, ServiceDefinition service) {
        try {
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .handle((response, failure) -> failure == null
                            ? classifyLookup(response, service)
                            : lookupRetryable(transportCode(request, failure), unwrap(failure), null));
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(
                    lookupRetryable(transportCode(request, failure), unwrap(failure), null));
        }
    }

    private CatalogLookupOutcome classifyLookup(
            HttpResponse<String> response, ServiceDefinition service) {
        if (isSuccess(response.statusCode())) {
            try {
                JsonNode instances = objectMapper.readTree(response.body());
                if (instances == null || !instances.isArray()) {
                    return new CatalogLookupOutcome.Retryable("invalid_response",
                            "Server returned a malformed catalog response", null);
                }
                for (JsonNode instance : instances) {
                    if (nodeId.equals(instance.path("nodeId").asText())
                            && tenant.equals(instance.path("tenantId").asText())
                            && namespace.equals(instance.path("namespace").asText())
                            && service.id().equals(instance.path("serviceId").asText())) {
                        return new CatalogLookupOutcome.Present();
                    }
                }
                return new CatalogLookupOutcome.Absent();
            } catch (IOException error) {
                return lookupRetryable("invalid_response", error, null);
            }
        }
        CatalogOutcome outcome = classifyError(response);
        if (outcome instanceof CatalogOutcome.Retryable failure) {
            return new CatalogLookupOutcome.Retryable(
                    failure.code(), failure.message(), failure.leaderId());
        }
        CatalogOutcome.Rejected failure = (CatalogOutcome.Rejected) outcome;
        return new CatalogLookupOutcome.Rejected(
                failure.code(), failure.message(), failure.leaderId());
    }

    @Override
    public CompletableFuture<Optional<Set<String>>> nodeServices() {
        ensureOpen();
        if (endpoints == null) {
            throw new IllegalStateException("No server seeds were configured for automatic selection");
        }
        return nodeServicesAttempt(endpoints.cycle(), 0);
    }

    private CompletableFuture<Optional<Set<String>>> nodeServicesAttempt(List<URI> cycle, int index) {
        URI endpoint = cycle.get(index);
        HttpRequest request = withToken(request(endpoint, "/v1/catalog/node-services/" + encode(nodeId)),
                tokens.node()).GET().build();
        CompletableFuture<Optional<Set<String>>> answer;
        try {
            answer = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .handle((response, failure) -> failure == null ? ownServices(response) : Optional.empty());
        } catch (RuntimeException failure) {
            answer = CompletableFuture.completedFuture(Optional.empty());
        }
        return answer.thenCompose(services -> {
            if (services.isPresent()) {
                endpoints.markSuccessful(endpoint);
                contactTracker.recordSuccessfulContact();
                return CompletableFuture.completedFuture(services);
            }
            return index + 1 == cycle.size() ? CompletableFuture.completedFuture(services)
                    : nodeServicesAttempt(cycle, index + 1);
        });
    }

    /** The IDs of this node's services in this client's scope, or empty when the answer does not give them. */
    private Optional<Set<String>> ownServices(HttpResponse<String> response) {
        if (!isSuccess(response.statusCode())) return Optional.empty();
        try {
            JsonNode instances = objectMapper.readTree(response.body());
            if (instances == null || !instances.isArray()) return Optional.empty();
            Set<String> services = new HashSet<>();
            for (JsonNode instance : instances) {
                if (nodeId.equals(instance.path("nodeId").asText())
                        && tenant.equals(instance.path("tenantId").asText())
                        && namespace.equals(instance.path("namespace").asText())) {
                    services.add(instance.path("serviceId").asText());
                }
            }
            return Optional.of(services);
        } catch (IOException error) {
            return Optional.empty();
        }
    }

    /**
     * Forwards a read across the server seeds for an application, under the application's request ID, to pass its
     * answer on as it came. A busy server or one that cannot be reached is passed over for the next seed. When every
     * seed has been tried, the answer is the last server's that did answer, whose envelope says more than silence, or
     * empty when none did.
     *
     * @param scope the scope headers the application sent, in place of this client's own
     */
    public CompletableFuture<Optional<ForwardedRead>> forwardRead(String pathAndQuery, String requestId,
                                                                  Map<String, String> scope) {
        ensureOpen();
        if (endpoints == null) {
            throw new IllegalStateException("No server seeds were configured for automatic selection");
        }
        return forwardAttempt(endpoints.cycle(), 0, pathAndQuery, requestId, scope, Optional.empty());
    }

    private CompletableFuture<Optional<ForwardedRead>> forwardAttempt(List<URI> cycle, int index, String pathAndQuery,
            String requestId, Map<String, String> scope, Optional<ForwardedRead> lastAnswer) {
        URI endpoint = cycle.get(index);
        HttpRequest.Builder builder = request(endpoint, pathAndQuery).setHeader("X-Request-Id", requestId)
                .timeout(requestTimeout.plus(blockingWait(pathAndQuery)));
        scope.forEach(builder::setHeader);
        HttpRequest request = builder.GET().build();
        CompletableFuture<Optional<ForwardedRead>> answer;
        try {
            answer = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .handle((response, failure) -> failure == null ? Optional.of(forwarded(response)) : Optional.empty());
        } catch (RuntimeException failure) {
            answer = CompletableFuture.completedFuture(Optional.empty());
        }
        return answer.thenCompose(read -> {
            if (read.isPresent() && read.get().status() != 429 && read.get().status() < 500) {
                endpoints.markSuccessful(endpoint);
                contactTracker.recordSuccessfulContact();
                return CompletableFuture.completedFuture(read);
            }
            Optional<ForwardedRead> latest = read.isPresent() ? read : lastAnswer;
            return index + 1 == cycle.size() ? CompletableFuture.completedFuture(latest)
                    : forwardAttempt(cycle, index + 1, pathAndQuery, requestId, scope, latest);
        });
    }

    private static final java.util.regex.Pattern WAIT = java.util.regex.Pattern.compile("(\\d+)(ms|s|m|h)");

    /**
     * How long a server may hold a blocking read that {@code pathAndQuery} asks for, beyond answering it: its
     * {@code wait}, 5 minutes by default, and the sixteenth the server may add, when {@code index} is above 0; zero
     * otherwise (consistency list, decision 6).
     */
    static Duration blockingWait(String pathAndQuery) {
        int query = pathAndQuery.indexOf('?');
        if (query < 0) return Duration.ZERO;
        boolean blocking = false;
        Duration wait = Duration.ofMinutes(5);
        for (String parameter : pathAndQuery.substring(query + 1).split("&")) {
            int equals = parameter.indexOf('=');
            String name = equals < 0 ? parameter : parameter.substring(0, equals);
            String value = equals < 0 ? "" : parameter.substring(equals + 1);
            if (name.equals("index")) {
                try {
                    blocking = Long.parseLong(value) > 0;
                } catch (NumberFormatException notIndex) {
                    blocking = false;
                }
            } else if (name.equals("wait")) {
                java.util.regex.Matcher given = WAIT.matcher(value);
                if (given.matches()) {
                    long amount = Long.parseLong(given.group(1));
                    wait = switch (given.group(2)) {
                        case "ms" -> Duration.ofMillis(amount);
                        case "s" -> Duration.ofSeconds(amount);
                        case "m" -> Duration.ofMinutes(amount);
                        default -> Duration.ofHours(amount);
                    };
                }
            }
        }
        return blocking ? wait.plus(wait.dividedBy(16)) : Duration.ZERO;
    }

    /** The headers of a server's answer that a forwarded request passes on to the application. */
    private static final java.util.List<String> PASSED_ON = java.util.List.of("Content-Type", "X-Qraft-Index",
            LEADER_ID_HEADER, "X-Qraft-Results-Filtered-By-ACLs");

    /**
     * Forwards an application's request across the server seeds and keeps the answer as it came (key/value list,
     * Phase 4). A read moves to the next seed as {@link #forwardRead} does. A write moves on only when it cannot have
     * been applied: a seed that could not be reached, one that is busy, or one that answers that it has no leader
     * or is draining; any other answer, an outcome the server does not know among them, is passed on, so that no
     * write is applied twice.
     */
    public CompletableFuture<Optional<ForwardedResponse>> forward(String method, String pathAndQuery, byte[] body,
                                                                  String requestId, Map<String, String> headers) {
        ensureOpen();
        if (endpoints == null) {
            throw new IllegalStateException("No server seeds were configured for automatic selection");
        }
        return forwardAttempt(endpoints.cycle(), 0, method, pathAndQuery, body, requestId, headers, Optional.empty());
    }

    private CompletableFuture<Optional<ForwardedResponse>> forwardAttempt(List<URI> cycle, int index, String method,
            String pathAndQuery, byte[] body, String requestId, Map<String, String> headers,
            Optional<ForwardedResponse> lastAnswer) {
        URI endpoint = cycle.get(index);
        boolean read = "GET".equals(method);
        HttpRequest.Builder builder = request(endpoint, pathAndQuery).setHeader("X-Request-Id", requestId)
                .timeout(requestTimeout.plus(blockingWait(pathAndQuery)))
                .method(method, body.length == 0 ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(builder::setHeader);
        HttpRequest request = builder.build();
        CompletableFuture<Attempt> answer;
        try {
            answer = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                    .handle((response, failure) -> {
                        if (failure == null) return new Attempt(Optional.of(kept(response)), false);
                        transportCode(request, failure);
                        return new Attempt(Optional.empty(), read || notSent(failure));
                    });
        } catch (RuntimeException failure) {
            answer = CompletableFuture.completedFuture(new Attempt(Optional.empty(), true));
        }
        return answer.thenCompose(attempt -> {
            Optional<ForwardedResponse> response = attempt.response();
            boolean moveOn = response.isEmpty() ? attempt.retryable() : passedOver(response.get(), read);
            if (!moveOn) {
                if (response.isPresent()) {
                    endpoints.markSuccessful(endpoint);
                    contactTracker.recordSuccessfulContact();
                }
                return CompletableFuture.completedFuture(response.isPresent() ? response : lastAnswer);
            }
            Optional<ForwardedResponse> latest = response.isPresent() ? response : lastAnswer;
            return index + 1 == cycle.size() ? CompletableFuture.completedFuture(latest)
                    : forwardAttempt(cycle, index + 1, method, pathAndQuery, body, requestId, headers, latest);
        });
    }

    /** One seed's answer, or none, and whether asking the next seed is safe. */
    private record Attempt(Optional<ForwardedResponse> response, boolean retryable) { }

    /** Whether the next seed is asked after {@code response}: a busy or failed server for a read, and for a write
     *  only a busy server, or one with no leader or draining, which applied nothing. */
    private boolean passedOver(ForwardedResponse response, boolean read) {
        if (response.status() == 429) return true;
        if (response.status() < 500) return false;
        if (read) return true;
        if (response.status() != 503) return false;
        try {
            String code = objectMapper.readTree(response.body()).path("code").asText();
            return "leader_unavailable".equals(code) || "draining".equals(code);
        } catch (IOException notJson) {
            return false;
        }
    }

    /** Whether {@code failure} happened before the request reached a server, so it cannot have been applied. */
    private static boolean notSent(Throwable failure) {
        for (Throwable current = unwrap(failure); current != null; current = current.getCause()) {
            if (current instanceof java.net.ConnectException) return true;
        }
        return false;
    }

    private static ForwardedResponse kept(HttpResponse<byte[]> response) {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        for (String name : PASSED_ON) response.headers().firstValue(name).ifPresent(value -> headers.put(name, value));
        return new ForwardedResponse(response.statusCode(), response.body() == null ? new byte[0] : response.body(),
                headers);
    }

    private static ForwardedRead forwarded(HttpResponse<String> response) {
        return new ForwardedRead(response.statusCode(), response.body(),
                response.headers().firstValue("X-Qraft-Index"), response.headers().firstValue(LEADER_ID_HEADER));
    }

    private static CatalogLookupOutcome.Retryable lookupRetryable(
            String code, Throwable failure, String leaderId) {
        return new CatalogLookupOutcome.Retryable(code,
                Objects.toString(failure.getMessage(), failure.getClass().getSimpleName()), leaderId);
    }

    /**
     * Publishes one health observation across the server seeds. Retryable outcomes rotate to the
     * next seed; an accepted or stale answer marks the endpoint successful and records server
     * contact; any other rejection ends the cycle.
     */
    @Override
    public CompletableFuture<ObservationOutcome> observe(CheckObservation observation) {
        ensureOpen();
        Objects.requireNonNull(observation, "observation");
        if (endpoints == null) {
            throw new IllegalStateException("No server seeds were configured for automatic selection");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serviceId", observation.serviceId());
        body.put("checkId", observation.checkId());
        body.put("status", observation.status().name().toLowerCase(Locale.ROOT));
        body.put("sequenceNumber", observation.sequenceNumber());
        body.put("observedAt", observation.observedAt().toString());
        body.put("ttlMillis", observation.ttl().toMillis());
        body.put("required", observation.required());
        body.put("output", observation.output());
        body.put("deregisterAfterMillis", observation.deregisterAfter().toMillis());
        try {
            return observeAttempt(endpoints.cycle(), 0, objectMapper.writeValueAsString(body),
                    tokens.service(observation.serviceId()));
        } catch (IOException error) {
            return CompletableFuture.completedFuture(new ObservationOutcome.Retryable("request_encoding",
                    Objects.toString(error.getMessage(), error.getClass().getSimpleName()), null));
        }
    }

    private CompletableFuture<ObservationOutcome> observeAttempt(List<URI> cycle, int index, String body,
                                                                 Optional<String> token) {
        URI endpoint = cycle.get(index);
        HttpRequest request = withToken(request(endpoint, "/v1/catalog/check/observe"), token)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build();
        return sendObservation(request).thenCompose(outcome -> {
            if (outcome instanceof ObservationOutcome.Accepted || outcome instanceof ObservationOutcome.Stale) {
                endpoints.markSuccessful(endpoint);
                contactTracker.recordSuccessfulContact();
                return CompletableFuture.completedFuture(outcome);
            }
            if (outcome instanceof ObservationOutcome.Rejected || index + 1 == cycle.size()) {
                return CompletableFuture.completedFuture(outcome);
            }
            return observeAttempt(cycle, index + 1, body, token);
        });
    }

    private CompletableFuture<ObservationOutcome> sendObservation(HttpRequest request) {
        try {
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .handle((response, failure) -> failure == null
                            ? classifyObservation(response)
                            : observationRetryable(retryable(transportCode(request, failure), unwrap(failure))));
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(
                    observationRetryable(retryable(transportCode(request, failure), unwrap(failure))));
        }
    }

    private ObservationOutcome classifyObservation(HttpResponse<String> response) {
        JsonNode body = parseObject(response.body());
        if (isSuccess(response.statusCode())) {
            JsonNode sequence = body == null ? null : body.path("sequenceNumber");
            String deadline = body == null ? null : textOrNull(body.path("deadline"));
            if (sequence != null && sequence.canConvertToLong() && sequence.isIntegralNumber() && deadline != null) {
                try {
                    return new ObservationOutcome.Accepted(sequence.longValue(), Instant.parse(deadline));
                } catch (DateTimeParseException ignored) {
                    // Falls through to the malformed-response outcome.
                }
            }
            return new ObservationOutcome.Retryable(
                    "invalid_response", "Server returned a malformed observation response", null);
        }
        if (response.statusCode() == 409 && isErrorEnvelope(body)
                && "stale_observation".equals(body.path("code").textValue())
                && body.path("currentSequenceNumber").isIntegralNumber()) {
            return new ObservationOutcome.Stale(body.path("currentSequenceNumber").longValue());
        }
        return switch (classifyError(response)) {
            case CatalogOutcome.Retryable failure ->
                    new ObservationOutcome.Retryable(failure.code(), failure.message(), failure.leaderId());
            case CatalogOutcome.Rejected failure ->
                    new ObservationOutcome.Rejected(failure.code(), failure.message(), failure.leaderId());
            case CatalogOutcome.Success ignored -> throw new IllegalStateException("error classified as success");
        };
    }

    private static ObservationOutcome.Retryable observationRetryable(CatalogOutcome.Retryable failure) {
        return new ObservationOutcome.Retryable(failure.code(), failure.message(), failure.leaderId());
    }

    /**
     * Registers the node this client speaks for. The identity header names the node, and the body describes it:
     * the status and the times of a node are the servers' to set, so they are not sent.
     */
    public CompletableFuture<CatalogOutcome> registerNode(Node node) {
        Objects.requireNonNull(node, "node");
        if (!nodeId.equals(node.name())) {
            throw new IllegalArgumentException("This client speaks for node " + nodeId + ", not " + node.name());
        }
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("address", node.address());
        description.put("datacenter", node.datacenter());
        description.put("region", node.region());
        description.put("metadata", node.metadata());
        return nodeWrite("/v1/catalog/register", Map.of("node", description), NodeOperation.REGISTER);
    }

    /** Sends a heartbeat of the node this client speaks for, as part of the registration it names. */
    public CompletableFuture<CatalogOutcome> heartbeatNode(long sequenceNumber, String registrationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sequenceNumber", sequenceNumber);
        if (registrationId != null) body.put("registrationId", registrationId);
        return nodeWrite("/v1/catalog/node/heartbeat", body, NodeOperation.HEARTBEAT);
    }

    /** Deregisters the node this client speaks for: a body that names no service removes the node itself. */
    public CompletableFuture<CatalogOutcome> deregisterNode() {
        return nodeWrite("/v1/catalog/deregister", Map.of(), NodeOperation.DEREGISTER);
    }

    private CompletableFuture<CatalogOutcome> nodeWrite(String path, Map<String, Object> body,
                                                        NodeOperation operation) {
        try {
            String json = objectMapper.writeValueAsString(body);
            return acrossSeeds(endpoint -> send(
                    withToken(request(endpoint, path), tokens.node()).header("Content-Type", "application/json")
                            .PUT(HttpRequest.BodyPublishers.ofString(json)).build(),
                    response -> classifyNode(response, operation)));
        } catch (IOException error) {
            return CompletableFuture.completedFuture(retryable("request_encoding", error));
        }
    }

    private CompletableFuture<CatalogOutcome> acrossSeeds(
            Function<URI, CompletableFuture<CatalogOutcome>> operation) {
        ensureOpen();
        if (endpoints == null) {
            throw new IllegalStateException("No server seeds were configured for automatic selection");
        }
        List<URI> cycle = endpoints.cycle();
        return attempt(cycle, 0, operation);
    }

    private CompletableFuture<CatalogOutcome> attempt(List<URI> cycle, int index,
            Function<URI, CompletableFuture<CatalogOutcome>> operation) {
        URI endpoint = cycle.get(index);
        return operation.apply(endpoint).thenCompose(outcome -> {
            if (outcome instanceof CatalogOutcome.Success) {
                endpoints.markSuccessful(endpoint);
                contactTracker.recordSuccessfulContact();
                return CompletableFuture.completedFuture(outcome);
            }
            if (outcome instanceof CatalogOutcome.Rejected || index + 1 == cycle.size()) {
                return CompletableFuture.completedFuture(outcome);
            }
            return attempt(cycle, index + 1, operation);
        });
    }

    private HttpRequest.Builder request(URI server, String path) {
        Objects.requireNonNull(server, "server");
        if (!"http".equalsIgnoreCase(server.getScheme()) && !"https".equalsIgnoreCase(server.getScheme())) {
            throw new IllegalArgumentException("server must use HTTP or HTTPS");
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(server.resolve(path))
                .timeout(requestTimeout)
                .header("X-Qraft-Node", nodeId)
                .header("X-Qraft-Tenant", tenant)
                .header("X-Qraft-Namespace", namespace)
                .header("X-Request-Id", UUID.randomUUID().toString())
                .header("Accept", "application/json");
        if (nodeUuid != null) request.header("X-Qraft-Node-ID", nodeUuid);
        return request;
    }

    private CompletableFuture<CatalogOutcome> send(HttpRequest request,
            Function<HttpResponse<String>, CatalogOutcome> classifier) {
        try {
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .handle((response, failure) -> failure == null
                            ? classifier.apply(response)
                            : retryable(transportCode(request, failure), unwrap(failure)));
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(retryable(transportCode(request, failure), unwrap(failure)));
        }
    }

    private CatalogOutcome classifyCatalog(HttpResponse<String> response, CatalogOperation operation) {
        if (isSuccess(response.statusCode())) return parseCatalogSuccess(response.body(), operation);
        return classifyError(response);
    }

    /** A node write succeeded only if the answer names the node and says what happened to it. */
    private CatalogOutcome classifyNode(HttpResponse<String> response, NodeOperation operation) {
        if (!isSuccess(response.statusCode())) return classifyError(response);
        JsonNode parsed = parseObject(response.body());
        String node = parsed == null ? null : textOrNull(parsed.path("node"));
        JsonNode changed = parsed == null ? null : parsed.path(operation.resultField);
        if (node == null || changed == null || !changed.isBoolean()) {
            return new CatalogOutcome.Retryable(
                    "invalid_response", "Server returned a malformed success response", null);
        }
        return new CatalogOutcome.Success(node, changed.booleanValue());
    }

    private CatalogOutcome classifyError(HttpResponse<String> response) {
        int status = response.statusCode();
        JsonNode error = parseObject(response.body());
        if (isErrorEnvelope(error)) {
            String code = error.path("code").textValue();
            String message = error.path("message").textValue();
            String leaderId = textOrNull(error.path("leaderId"));
            if (leaderId == null) leaderId = response.headers().firstValue(LEADER_ID_HEADER).orElse(null);
            return error.path("retryable").booleanValue()
                    ? new CatalogOutcome.Retryable(code, message, leaderId)
                    : new CatalogOutcome.Rejected(code, message, leaderId);
        }
        String code = "http_" + status;
        String message = "HTTP " + status + " returned a malformed error response";
        if (status == 429 || status >= 500) return new CatalogOutcome.Retryable(code, message, null);
        return new CatalogOutcome.Rejected(code, message, null);
    }

    private CatalogOutcome parseCatalogSuccess(String body, CatalogOperation operation) {
        JsonNode parsed = parseObject(body);
        // A registration answers with the node and, under "service", what happened to the service.
        if (parsed != null && operation == CatalogOperation.REGISTER) {
            JsonNode service = parsed.path("service");
            parsed = service.isObject() ? service : null;
        }
        String serviceId = parsed == null ? null : textOrNull(parsed.path("serviceId"));
        JsonNode changed = parsed == null ? null : parsed.path(operation.resultField);
        if (serviceId == null || changed == null || !changed.isBoolean()) {
            return new CatalogOutcome.Retryable(
                    "invalid_response", "Server returned a malformed success response", null);
        }
        return new CatalogOutcome.Success(serviceId, changed.booleanValue());
    }

    private JsonNode parseObject(String body) {
        try {
            JsonNode parsed = objectMapper.readTree(body);
            return parsed != null && parsed.isObject() ? parsed : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static boolean isErrorEnvelope(JsonNode node) {
        return node != null && textOrNull(node.path("code")) != null
                && textOrNull(node.path("message")) != null && node.path("retryable").isBoolean();
    }

    private static boolean isSuccess(int status) { return status >= 200 && status < 300; }

    private static CatalogOutcome.Retryable retryable(String code, Throwable failure) {
        return new CatalogOutcome.Retryable(code,
                Objects.toString(failure.getMessage(), failure.getClass().getSimpleName()), null);
    }

    /**
     * The outcome code of a call that failed in transport. A failed TLS handshake is logged once per server and
     * reason, since retrying cannot mend it: a certificate refused, or a server the CA did not sign (transport
     * security list, Phase 3).
     */
    private String transportCode(HttpRequest request, Throwable failure) {
        Throwable cause = unwrap(failure);
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof javax.net.ssl.SSLException tls) {
                URI uri = request.uri();
                String server = uri.getScheme() + "://" + uri.getAuthority();
                String reason = Objects.toString(tls.getMessage(), tls.getClass().getSimpleName());
                if (tlsWarnings.add(server + " " + reason)) {
                    LOGGER.warn("TLS with server {} failed, so the client cannot use that server until it is fixed: "
                            + "{}. Check client.tls and the server's certificate", server, reason);
                }
                break;
            }
        }
        return cause instanceof java.net.http.HttpTimeoutException ? "request_timeout" : "transport_error";
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) current = current.getCause();
        return current;
    }

    private static String textOrNull(JsonNode node) {
        return node != null && node.isTextual() && !node.textValue().isBlank() ? node.textValue() : null;
    }

    private static String required(String name, String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("Catalog client is closed");
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) httpClient.close();
    }

    /** Cancels outstanding requests when a lifecycle deadline has already expired. */
    public void closeNow() {
        if (closed.compareAndSet(false, true)) httpClient.shutdownNow();
    }

    public boolean isClosed() { return closed.get(); }

    private enum CatalogOperation {
        REGISTER("registered"), DEREGISTER("deregistered");
        private final String resultField;
        CatalogOperation(String resultField) { this.resultField = resultField; }
    }

    private enum NodeOperation {
        REGISTER("registered"), HEARTBEAT("accepted"), DEREGISTER("deregistered");
        private final String resultField;
        NodeOperation(String resultField) { this.resultField = resultField; }
    }
}
