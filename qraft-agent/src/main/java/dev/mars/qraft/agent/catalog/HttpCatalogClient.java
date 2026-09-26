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
import dev.mars.qraft.agent.AgentInfo;
import dev.mars.qraft.agent.health.CheckObservation;
import dev.mars.qraft.agent.health.ObservationClient;
import dev.mars.qraft.agent.health.ObservationOutcome;
import dev.mars.qraft.catalog.ServiceDefinition;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * JDK HTTP implementation shared by catalog and agent control-plane requests.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-24
 * @version 1.0
 */
public final class HttpCatalogClient implements CatalogClient, ObservationClient {
    private static final String LEADER_ID_HEADER = "X-Qraft-Leader-Id";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ControllerEndpoints endpoints;
    private final String nodeId;
    private final String tenant;
    private final String namespace;
    private final String datacenter;
    private final String region;
    private final Duration requestTimeout;
    private final ControllerContactTracker contactTracker;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Single-endpoint constructor retained for protocol fixtures and direct attempts. */
    public HttpCatalogClient(HttpClient httpClient, ObjectMapper objectMapper,
                             String nodeId, String tenant, String namespace,
                             String datacenter, String region, Duration requestTimeout) {
        this(httpClient, objectMapper, (ControllerEndpoints) null, nodeId, tenant, namespace,
                datacenter, region, requestTimeout, new ControllerContactTracker(Clock.systemUTC()));
    }

    /** Production constructor with ordered controller-seed selection. */
    public HttpCatalogClient(HttpClient httpClient, ObjectMapper objectMapper, List<URI> controllers,
                             String nodeId, String tenant, String namespace,
                             String datacenter, String region, Duration requestTimeout) {
        this(httpClient, objectMapper, new ControllerEndpoints(controllers), nodeId, tenant, namespace,
                datacenter, region, requestTimeout, new ControllerContactTracker(Clock.systemUTC()));
    }

    public HttpCatalogClient(HttpClient httpClient, ObjectMapper objectMapper, List<URI> controllers,
                             String nodeId, String tenant, String namespace,
                             String datacenter, String region, Duration requestTimeout,
                             ControllerContactTracker contactTracker) {
        this(httpClient, objectMapper, new ControllerEndpoints(controllers), nodeId, tenant, namespace,
                datacenter, region, requestTimeout, contactTracker);
    }

    private HttpCatalogClient(HttpClient httpClient, ObjectMapper objectMapper, ControllerEndpoints endpoints,
                              String nodeId, String tenant, String namespace,
                              String datacenter, String region, Duration requestTimeout,
                              ControllerContactTracker contactTracker) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").findAndRegisterModules();
        this.endpoints = endpoints;
        this.nodeId = required("nodeId", nodeId);
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

    @Override
    public CompletableFuture<CatalogOutcome> register(ServiceDefinition service) {
        return acrossSeeds(endpoint -> register(endpoint, service));
    }

    @Override
    public CompletableFuture<CatalogOutcome> deregister(String serviceId) {
        return acrossSeeds(endpoint -> deregister(endpoint, serviceId));
    }

    public CompletableFuture<CatalogOutcome> register(URI controller, ServiceDefinition service) {
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
        try {
            HttpRequest request = request(controller, "/v1/agent/service/register")
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            return send(request, response -> classifyCatalog(response, CatalogOperation.REGISTER));
        } catch (IOException error) {
            return CompletableFuture.completedFuture(retryable("request_encoding", error));
        }
    }

    public CompletableFuture<CatalogOutcome> deregister(URI controller, String serviceId) {
        ensureOpen();
        String id = required("serviceId", serviceId);
        HttpRequest request = request(controller, "/v1/agent/service/deregister/" + encode(id))
                .PUT(HttpRequest.BodyPublishers.noBody()).build();
        return send(request, response -> classifyCatalog(response, CatalogOperation.DEREGISTER));
    }

    @Override
    public CompletableFuture<CatalogLookupOutcome> lookup(ServiceDefinition service) {
        ensureOpen();
        Objects.requireNonNull(service, "service");
        if (endpoints == null) {
            throw new IllegalStateException("No controller seeds were configured for automatic selection");
        }
        List<URI> cycle = endpoints.cycle();
        return lookupAttempt(cycle, 0, service);
    }

    private CompletableFuture<CatalogLookupOutcome> lookupAttempt(
            List<URI> cycle, int index, ServiceDefinition service) {
        URI endpoint = cycle.get(index);
        HttpRequest request = request(endpoint, "/v1/catalog/service/" + encode(service.name()))
                .GET().build();
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
                            : lookupRetryable(transportCode(failure), unwrap(failure), null));
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(
                    lookupRetryable(transportCode(failure), unwrap(failure), null));
        }
    }

    private CatalogLookupOutcome classifyLookup(
            HttpResponse<String> response, ServiceDefinition service) {
        if (isSuccess(response.statusCode())) {
            try {
                JsonNode instances = objectMapper.readTree(response.body());
                if (instances == null || !instances.isArray()) {
                    return new CatalogLookupOutcome.Retryable("invalid_response",
                            "Controller returned a malformed catalog response", null);
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

    private static CatalogLookupOutcome.Retryable lookupRetryable(
            String code, Throwable failure, String leaderId) {
        return new CatalogLookupOutcome.Retryable(code,
                Objects.toString(failure.getMessage(), failure.getClass().getSimpleName()), leaderId);
    }

    /**
     * Publishes one health observation across the controller seeds. Retryable outcomes rotate to the
     * next seed; an accepted or stale answer marks the endpoint successful and records controller
     * contact; any other rejection ends the cycle.
     */
    @Override
    public CompletableFuture<ObservationOutcome> observe(CheckObservation observation) {
        ensureOpen();
        Objects.requireNonNull(observation, "observation");
        if (endpoints == null) {
            throw new IllegalStateException("No controller seeds were configured for automatic selection");
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
        try {
            return observeAttempt(endpoints.cycle(), 0, objectMapper.writeValueAsString(body));
        } catch (IOException error) {
            return CompletableFuture.completedFuture(new ObservationOutcome.Retryable("request_encoding",
                    Objects.toString(error.getMessage(), error.getClass().getSimpleName()), null));
        }
    }

    private CompletableFuture<ObservationOutcome> observeAttempt(List<URI> cycle, int index, String body) {
        URI endpoint = cycle.get(index);
        HttpRequest request = request(endpoint, "/v1/agent/check/observe")
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
            return observeAttempt(cycle, index + 1, body);
        });
    }

    private CompletableFuture<ObservationOutcome> sendObservation(HttpRequest request) {
        try {
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .handle((response, failure) -> failure == null
                            ? classifyObservation(response)
                            : observationRetryable(retryable(transportCode(failure), unwrap(failure))));
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(
                    observationRetryable(retryable(transportCode(failure), unwrap(failure))));
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
                    "invalid_response", "Controller returned a malformed observation response", null);
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

    public CompletableFuture<CatalogOutcome> registerAgent(AgentInfo agent) {
        Objects.requireNonNull(agent, "agent");
        try {
            String body = objectMapper.writeValueAsString(agent);
            return acrossSeeds(endpoint -> send(nodeRequest(endpoint, "/api/v1/agents/register", "POST", body),
                    response -> classifyNode(response, NodeOperation.REGISTER, agent.getAgentId())));
        } catch (IOException error) {
            return CompletableFuture.completedFuture(retryable("request_encoding", error));
        }
    }

    public CompletableFuture<CatalogOutcome> heartbeatAgent(Map<String, Object> heartbeat) {
        Objects.requireNonNull(heartbeat, "heartbeat");
        String agentId = required("agentId", Objects.toString(heartbeat.get("agentId"), null));
        try {
            String body = objectMapper.writeValueAsString(heartbeat);
            return acrossSeeds(endpoint -> send(nodeRequest(endpoint, "/api/v1/agents/heartbeat", "POST", body),
                    response -> classifyNode(response, NodeOperation.HEARTBEAT, agentId)));
        } catch (IOException error) {
            return CompletableFuture.completedFuture(retryable("request_encoding", error));
        }
    }

    public CompletableFuture<CatalogOutcome> deregisterAgent(String agentId) {
        String id = required("agentId", agentId);
        return acrossSeeds(endpoint -> send(
                nodeRequest(endpoint, "/api/v1/agents/" + encode(id), "DELETE", null),
                response -> classifyNode(response, NodeOperation.DEREGISTER, id)));
    }

    private CompletableFuture<CatalogOutcome> acrossSeeds(
            Function<URI, CompletableFuture<CatalogOutcome>> operation) {
        ensureOpen();
        if (endpoints == null) {
            throw new IllegalStateException("No controller seeds were configured for automatic selection");
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

    private HttpRequest nodeRequest(URI endpoint, String path, String method, String body) {
        HttpRequest.Builder builder = request(endpoint, path);
        if (body == null) return builder.method(method, HttpRequest.BodyPublishers.noBody()).build();
        return builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private HttpRequest.Builder request(URI controller, String path) {
        Objects.requireNonNull(controller, "controller");
        if (!"http".equalsIgnoreCase(controller.getScheme()) && !"https".equalsIgnoreCase(controller.getScheme())) {
            throw new IllegalArgumentException("controller must use HTTP or HTTPS");
        }
        return HttpRequest.newBuilder(controller.resolve(path))
                .timeout(requestTimeout)
                .header("X-Qraft-Node", nodeId)
                .header("X-Qraft-Tenant", tenant)
                .header("X-Qraft-Namespace", namespace)
                .header("X-Request-Id", UUID.randomUUID().toString())
                .header("Accept", "application/json");
    }

    private CompletableFuture<CatalogOutcome> send(HttpRequest request,
            Function<HttpResponse<String>, CatalogOutcome> classifier) {
        try {
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .handle((response, failure) -> failure == null
                            ? classifier.apply(response)
                            : retryable(transportCode(failure), unwrap(failure)));
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(retryable(transportCode(failure), unwrap(failure)));
        }
    }

    private CatalogOutcome classifyCatalog(HttpResponse<String> response, CatalogOperation operation) {
        if (isSuccess(response.statusCode())) return parseCatalogSuccess(response.body(), operation);
        return classifyError(response);
    }

    private CatalogOutcome classifyNode(HttpResponse<String> response, NodeOperation operation, String agentId) {
        int status = response.statusCode();
        if (operation.accepts(status)) return new CatalogOutcome.Success(agentId, status != 404);
        return classifyError(response);
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
        String serviceId = parsed == null ? null : textOrNull(parsed.path("serviceId"));
        JsonNode changed = parsed == null ? null : parsed.path(operation.resultField);
        if (serviceId == null || changed == null || !changed.isBoolean()) {
            return new CatalogOutcome.Retryable(
                    "invalid_response", "Controller returned a malformed success response", null);
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

    private static String transportCode(Throwable failure) {
        return unwrap(failure) instanceof java.net.http.HttpTimeoutException
                ? "request_timeout" : "transport_error";
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
        REGISTER, HEARTBEAT, DEREGISTER;
        boolean accepts(int status) {
            return switch (this) {
                case REGISTER -> status == 200 || status == 201;
                case HEARTBEAT -> status == 200 || status == 204;
                case DEREGISTER -> status == 200 || status == 204 || status == 404;
            };
        }
    }
}
