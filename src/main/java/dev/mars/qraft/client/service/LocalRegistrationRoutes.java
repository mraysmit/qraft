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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import dev.mars.qraft.client.config.DefinitionJson;
import dev.mars.qraft.client.health.CheckResult;
import dev.mars.qraft.client.health.CheckStatus;
import dev.mars.qraft.client.health.HealthCheckDefinition;
import dev.mars.qraft.client.health.LocalStatusReporter;
import dev.mars.qraft.common.ServiceDefinition;
import dev.mars.qraft.common.config.JsonSettings;
import dev.mars.qraft.common.http.JsonApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.BiFunction;

/**
 * The local API's service, check, TTL status and maintenance routes, after Consul's agent API:
 * <pre>
 * PUT /v1/client/service/register[?replace-existing-checks]
 * PUT /v1/client/service/deregister/{serviceId}
 * GET /v1/client/services
 * GET /v1/client/service/{serviceId}
 * PUT /v1/client/check/register
 * PUT /v1/client/check/deregister/{checkId}
 * GET /v1/client/checks
 * PUT /v1/client/check/pass/{checkId}[?note=...], warn, fail
 * PUT /v1/client/check/update/{checkId}
 * PUT /v1/client/maintenance?enable=...[&amp;reason=...]
 * PUT /v1/client/service/maintenance/{serviceId}?enable=...[&amp;reason=...]
 * </pre>
 *
 * <p>A write answers once the change is kept and has taken effect locally. The servers learn of it from the
 * reconciler, as Consul's agent syncs after it answers. As in Consul, a check for a service that is not registered
 * and a read of an unknown service answer 404, and removing what is not registered is not an error.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-31
 * @version 1.0
 */
public final class LocalRegistrationRoutes {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalRegistrationRoutes.class);
    private static final String SERVICE_REGISTER = "/v1/client/service/register";
    private static final String SERVICE_DEREGISTER = "/v1/client/service/deregister/";
    private static final String SERVICES = "/v1/client/services";
    private static final String SERVICE = "/v1/client/service/";
    private static final String CHECK_REGISTER = "/v1/client/check/register";
    private static final String CHECK_DEREGISTER = "/v1/client/check/deregister/";
    private static final String CHECKS = "/v1/client/checks";
    private static final String CHECK_PASS = "/v1/client/check/pass/";
    private static final String CHECK_WARN = "/v1/client/check/warn/";
    private static final String CHECK_FAIL = "/v1/client/check/fail/";
    private static final String CHECK_UPDATE = "/v1/client/check/update/";
    private static final String NODE_MAINTENANCE = "/v1/client/maintenance";
    private static final String SERVICE_MAINTENANCE = "/v1/client/service/maintenance/";
    private static final String REPLACE_EXISTING_CHECKS = "replace-existing-checks";
    private static final String INVALID_REGISTRATION = "invalid_registration";
    private static final String INVALID_REQUEST = "invalid_request";
    private static final String DEFAULT_SERVICE_REASON =
            "Maintenance is enabled for this service, but no reason was given";
    private static final String DEFAULT_NODE_REASON = "Maintenance is enabled for this node, but no reason was given";

    private final ObjectMapper json = new ObjectMapper();
    private final JsonApi api = new JsonApi(json);
    private final LocalDefinitions definitions;
    private final BiFunction<String, String, Optional<CheckResult>> latestResult;
    private final BiFunction<String, String, Optional<LocalStatusReporter>> statusReporter;
    private final String clientAddress;

    /** A change to the definitions, which may fail to be kept. */
    @FunctionalInterface
    private interface Change {
        void run() throws IOException;
    }

    /**
     * @param latestResult   the latest result of a check, by service ID and check ID
     * @param statusReporter the status input of a running TTL check, by service ID and check ID
     * @param clientAddress  the address of a service that gives none
     */
    public LocalRegistrationRoutes(LocalDefinitions definitions,
                                   BiFunction<String, String, Optional<CheckResult>> latestResult,
                                   BiFunction<String, String, Optional<LocalStatusReporter>> statusReporter,
                                   String clientAddress) {
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.latestResult = Objects.requireNonNull(latestResult, "latestResult");
        this.statusReporter = Objects.requireNonNull(statusReporter, "statusReporter");
        this.clientAddress = Objects.requireNonNull(clientAddress, "clientAddress");
    }

    /** Serves the routes on {@code localApi}, which refuses their writes once shutdown begins. */
    public void install(LocalApiServer localApi) {
        localApi.route(SERVICE_REGISTER, this::registerService);
        localApi.route(SERVICE_DEREGISTER, this::deregisterService);
        localApi.route(SERVICES, this::listServices);
        localApi.route(SERVICE, this::readService);
        localApi.route(CHECK_REGISTER, this::registerCheck);
        localApi.route(CHECK_DEREGISTER, this::deregisterCheck);
        localApi.route(CHECKS, this::listChecks);
        localApi.route(CHECK_PASS, exchange -> reportNote(exchange, CHECK_PASS, CheckStatus.PASSING));
        localApi.route(CHECK_WARN, exchange -> reportNote(exchange, CHECK_WARN, CheckStatus.WARNING));
        localApi.route(CHECK_FAIL, exchange -> reportNote(exchange, CHECK_FAIL, CheckStatus.CRITICAL));
        localApi.route(CHECK_UPDATE, this::updateCheck);
        localApi.route(NODE_MAINTENANCE, this::nodeMaintenance);
        localApi.route(SERVICE_MAINTENANCE, this::serviceMaintenance);
    }

    private void registerService(HttpExchange exchange) throws IOException {
        if (!exactly(exchange, SERVICE_REGISTER) || !api.requireMethod(exchange, "PUT")) return;
        JsonNode body = readObject(exchange, INVALID_REGISTRATION);
        if (body == null) return;
        DefinitionJson.Registration registration;
        try {
            registration = DefinitionJson.parseRegistration(body, clientAddress);
        } catch (IllegalArgumentException invalid) {
            api.error(exchange, 400, INVALID_REGISTRATION, invalid.getMessage(), false, Map.of());
            return;
        }
        boolean replaceExistingChecks = asksToReplaceExistingChecks(exchange);
        if (!keep(exchange, () -> definitions.register(registration, replaceExistingChecks))) return;
        api.json(exchange, 200, Map.of("serviceId", registration.service().id()));
    }

    private void deregisterService(HttpExchange exchange) throws IOException {
        String serviceId = pathId(exchange, SERVICE_DEREGISTER);
        if (serviceId == null || !api.requireMethod(exchange, "PUT")) return;
        boolean[] removed = new boolean[1];
        if (!keep(exchange, () -> removed[0] = definitions.deregisterService(serviceId))) return;
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("serviceId", serviceId);
        answer.put("deregistered", removed[0]);
        api.json(exchange, 200, answer);
    }

    private void listServices(HttpExchange exchange) throws IOException {
        if (!exactly(exchange, SERVICES) || !api.requireMethod(exchange, "GET")) return;
        Map<String, ObjectNode> services = new TreeMap<>();
        for (ServiceDefinition service : definitions.services()) {
            if (service.enabled()) services.put(service.id(), DefinitionJson.write(service));
        }
        api.json(exchange, 200, services);
    }

    private void readService(HttpExchange exchange) throws IOException {
        String serviceId = pathId(exchange, SERVICE);
        if (serviceId == null || !api.requireMethod(exchange, "GET")) return;
        Optional<ServiceDefinition> service = definitions.service(serviceId);
        if (service.isEmpty()) {
            serviceNotFound(exchange, serviceId);
            return;
        }
        api.json(exchange, 200, DefinitionJson.write(service.get()));
    }

    private void registerCheck(HttpExchange exchange) throws IOException {
        if (!exactly(exchange, CHECK_REGISTER) || !api.requireMethod(exchange, "PUT")) return;
        JsonNode body = readObject(exchange, INVALID_REGISTRATION);
        if (body == null) return;
        String serviceId;
        HealthCheckDefinition check;
        try {
            serviceId = DefinitionJson.serviceIdOf(body);
            Optional<ServiceDefinition> service = definitions.service(serviceId);
            if (service.isEmpty()) {
                serviceNotFound(exchange, serviceId);
                return;
            }
            check = DefinitionJson.parseCheck(body, service.get());
        } catch (IllegalArgumentException invalid) {
            api.error(exchange, 400, INVALID_REGISTRATION, invalid.getMessage(), false, Map.of());
            return;
        }
        boolean[] registered = new boolean[1];
        if (!keep(exchange, () -> registered[0] = definitions.registerCheck(check))) return;
        if (!registered[0]) {
            // Its service was deregistered after the check was read for it.
            serviceNotFound(exchange, serviceId);
            return;
        }
        api.json(exchange, 200, Map.of("checkId", check.checkId(), "serviceId", serviceId));
    }

    private void deregisterCheck(HttpExchange exchange) throws IOException {
        String checkId = pathId(exchange, CHECK_DEREGISTER);
        if (checkId == null || !api.requireMethod(exchange, "PUT")) return;
        boolean[] removed = new boolean[1];
        if (!keep(exchange, () -> removed[0] = definitions.deregisterCheck(checkId))) return;
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("checkId", checkId);
        answer.put("deregistered", removed[0]);
        api.json(exchange, 200, answer);
    }

    private void listChecks(HttpExchange exchange) throws IOException {
        if (!exactly(exchange, CHECKS) || !api.requireMethod(exchange, "GET")) return;
        Map<String, ObjectNode> checks = new TreeMap<>();
        for (HealthCheckDefinition check : definitions.checks()) {
            ObjectNode written = DefinitionJson.write(check);
            latestResult.apply(check.serviceId(), check.checkId()).ifPresent(result -> {
                written.put("status", result.status().name().toLowerCase(Locale.ROOT));
                written.put("output", result.output());
                written.put("observedAt", result.observedAt().toString());
            });
            checks.put(check.checkId(), written);
        }
        api.json(exchange, 200, checks);
    }

    private void reportNote(HttpExchange exchange, String prefix, CheckStatus status) throws IOException {
        String checkId = pathId(exchange, prefix);
        if (checkId == null || !api.requireMethod(exchange, "PUT")) return;
        report(exchange, checkId, status, query(exchange).getOrDefault("note", ""));
    }

    private void updateCheck(HttpExchange exchange) throws IOException {
        String checkId = pathId(exchange, CHECK_UPDATE);
        if (checkId == null || !api.requireMethod(exchange, "PUT")) return;
        JsonNode body = readObject(exchange, INVALID_REQUEST);
        if (body == null) return;
        CheckStatus status;
        String output;
        try {
            JsonSettings.rejectUnknown(body, "check update", "status", "output");
            status = updatedStatus(body.get("status"));
            JsonNode given = body.get("output");
            if (given != null && !given.isTextual()) throw new IllegalArgumentException("output must be a string");
            output = given == null ? "" : given.textValue();
        } catch (IllegalArgumentException invalid) {
            api.error(exchange, 400, INVALID_REQUEST, invalid.getMessage(), false, Map.of());
            return;
        }
        report(exchange, checkId, status, output);
    }

    /** As Consul's, an update sets one of three statuses: maintenance has routes of its own. */
    private static CheckStatus updatedStatus(JsonNode status) {
        String value = status != null && status.isTextual() ? status.textValue() : "";
        return switch (value) {
            case "passing" -> CheckStatus.PASSING;
            case "warning" -> CheckStatus.WARNING;
            case "critical" -> CheckStatus.CRITICAL;
            default -> throw new IllegalArgumentException("Invalid check status: " + status);
        };
    }

    /** Tells a TTL check its status. A check can take one only once it runs: from the node's registration on. */
    private void report(HttpExchange exchange, String checkId, CheckStatus status, String output) throws IOException {
        Optional<HealthCheckDefinition> check = definitions.check(checkId);
        if (check.isEmpty()) {
            api.error(exchange, 404, "check_not_found", "Unknown check ID: " + checkId, false,
                    Map.of("checkId", checkId));
            return;
        }
        Optional<LocalStatusReporter> reporter = statusReporter.apply(check.get().serviceId(), checkId);
        if (reporter.isEmpty()) {
            api.error(exchange, 400, "check_not_ttl", "Check " + checkId + " does not take a status: it is not a "
                    + "TTL check", false, Map.of("checkId", checkId));
            return;
        }
        if (!reporter.get().report(status, output)) {
            api.error(exchange, 503, "check_not_running", "Check " + checkId + " is not running yet", true,
                    Map.of("checkId", checkId));
            return;
        }
        api.json(exchange, 200, Map.of("checkId", checkId, "status", status.name().toLowerCase(Locale.ROOT)));
    }

    private void nodeMaintenance(HttpExchange exchange) throws IOException {
        if (!exactly(exchange, NODE_MAINTENANCE) || !api.requireMethod(exchange, "PUT")) return;
        Map<String, String> query = query(exchange);
        Boolean enable = enable(exchange, query);
        if (enable == null) return;
        String reason = reason(query, DEFAULT_NODE_REASON);
        if (!keep(exchange, () -> definitions.setNodeMaintenance(enable, reason))) return;
        api.json(exchange, 200, Map.of("maintenance", enable));
    }

    private void serviceMaintenance(HttpExchange exchange) throws IOException {
        String serviceId = pathId(exchange, SERVICE_MAINTENANCE);
        if (serviceId == null || !api.requireMethod(exchange, "PUT")) return;
        Map<String, String> query = query(exchange);
        Boolean enable = enable(exchange, query);
        if (enable == null) return;
        String reason = reason(query, DEFAULT_SERVICE_REASON);
        boolean[] registered = new boolean[1];
        if (!keep(exchange, () -> registered[0] = definitions.setServiceMaintenance(serviceId, enable, reason))) return;
        if (!registered[0]) {
            serviceNotFound(exchange, serviceId);
            return;
        }
        api.json(exchange, 200, Map.of("serviceId", serviceId, "maintenance", enable));
    }

    /** The required {@code enable}, read as Go's {@code strconv.ParseBool} reads it, as Consul does; else refused. */
    private Boolean enable(HttpExchange exchange, Map<String, String> query) throws IOException {
        String raw = query.get("enable");
        Boolean enable = raw == null ? null : switch (raw) {
            case "1", "t", "T", "TRUE", "true", "True" -> Boolean.TRUE;
            case "0", "f", "F", "FALSE", "false", "False" -> Boolean.FALSE;
            default -> null;
        };
        if (enable == null) {
            api.error(exchange, 400, INVALID_REQUEST, raw == null ? "Missing value for enable"
                    : "Invalid value for enable: " + raw, false, Map.of());
        }
        return enable;
    }

    private static String reason(Map<String, String> query, String defaultReason) {
        String reason = query.get("reason");
        return reason == null || reason.isBlank() ? defaultReason : reason;
    }

    /** Runs {@code change}; when it cannot be kept, answers so and says false. Nothing has changed then. */
    private boolean keep(HttpExchange exchange, Change change) throws IOException {
        try {
            change.run();
            return true;
        } catch (IOException failure) {
            LOGGER.warn("A registration could not be kept in the data directory: {}", failure.toString());
            api.error(exchange, 500, "registration_not_kept", "The registration could not be kept in the data "
                    + "directory, so nothing changed", true, Map.of());
            return false;
        }
    }

    /** The request's JSON object; anything else is answered as invalid, with {@code code}, and gives {@code null}. */
    private JsonNode readObject(HttpExchange exchange, String code) throws IOException {
        JsonNode body;
        try {
            body = json.readTree(exchange.getRequestBody());
        } catch (JsonProcessingException malformed) {
            api.error(exchange, 400, code, "The request body is not valid JSON", false, Map.of());
            return null;
        }
        if (body == null || !body.isObject()) {
            api.error(exchange, 400, code, "The request body must be a JSON object", false, Map.of());
            return null;
        }
        return body;
    }

    private void serviceNotFound(HttpExchange exchange, String serviceId) throws IOException {
        api.error(exchange, 404, "service_not_found", "Unknown service ID: " + serviceId, false,
                Map.of("serviceId", serviceId));
    }

    /** Whether the path is {@code route} itself; a context also matches what continues it, which is not found. */
    private boolean exactly(HttpExchange exchange, String route) throws IOException {
        if (route.equals(exchange.getRequestURI().getPath())) return true;
        notFound(exchange);
        return false;
    }

    /** The ID that follows {@code prefix} in the path; without one the path is not found, and this gives null. */
    private String pathId(HttpExchange exchange, String prefix) throws IOException {
        String id = exchange.getRequestURI().getPath().substring(prefix.length());
        if (!id.isBlank()) return id;
        notFound(exchange);
        return null;
    }

    private void notFound(HttpExchange exchange) throws IOException {
        api.error(exchange, 404, "not_found", "No such route: " + exchange.getRequestURI().getPath(), false, Map.of());
    }

    /** As Consul reads its parameter: with no value, or with {@code true}. */
    private static boolean asksToReplaceExistingChecks(HttpExchange exchange) {
        String value = query(exchange).get(REPLACE_EXISTING_CHECKS);
        return value != null && (value.isEmpty() || value.equals("true"));
    }

    /** The query's parameters, decoded; a parameter with no value has an empty one. */
    private static Map<String, String> query(HttpExchange exchange) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) return Map.of();
        Map<String, String> parameters = new LinkedHashMap<>();
        for (String parameter : raw.split("&")) {
            int equals = parameter.indexOf('=');
            String name = equals < 0 ? parameter : parameter.substring(0, equals);
            String value = equals < 0 ? "" : parameter.substring(equals + 1);
            parameters.put(URLDecoder.decode(name, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return parameters;
    }
}
