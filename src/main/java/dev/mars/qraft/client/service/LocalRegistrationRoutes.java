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
import dev.mars.qraft.client.health.HealthCheckDefinition;
import dev.mars.qraft.common.ServiceDefinition;
import dev.mars.qraft.common.http.JsonApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.BiFunction;

/**
 * The local API's service and check routes, after Consul's agent API:
 * <pre>
 * PUT /v1/client/service/register[?replace-existing-checks]
 * PUT /v1/client/service/deregister/{serviceId}
 * GET /v1/client/services
 * GET /v1/client/service/{serviceId}
 * PUT /v1/client/check/register
 * PUT /v1/client/check/deregister/{checkId}
 * GET /v1/client/checks
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
    private static final String REPLACE_EXISTING_CHECKS = "replace-existing-checks";
    private static final String INVALID_REGISTRATION = "invalid_registration";

    private final ObjectMapper json = new ObjectMapper();
    private final JsonApi api = new JsonApi(json);
    private final LocalDefinitions definitions;
    private final BiFunction<String, String, Optional<CheckResult>> latestResult;
    private final String clientAddress;

    /** A change to the definitions, which may fail to be kept. */
    @FunctionalInterface
    private interface Change {
        void run() throws IOException;
    }

    /**
     * @param latestResult  the latest result of a check, by service ID and check ID
     * @param clientAddress the address of a service that gives none
     */
    public LocalRegistrationRoutes(LocalDefinitions definitions,
                                   BiFunction<String, String, Optional<CheckResult>> latestResult,
                                   String clientAddress) {
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.latestResult = Objects.requireNonNull(latestResult, "latestResult");
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
    }

    private void registerService(HttpExchange exchange) throws IOException {
        if (!exactly(exchange, SERVICE_REGISTER) || !api.requireMethod(exchange, "PUT")) return;
        JsonNode body = readObject(exchange);
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
        JsonNode body = readObject(exchange);
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

    /** The request's JSON object; anything else is answered as invalid and gives {@code null}. */
    private JsonNode readObject(HttpExchange exchange) throws IOException {
        JsonNode body;
        try {
            body = json.readTree(exchange.getRequestBody());
        } catch (JsonProcessingException malformed) {
            api.error(exchange, 400, INVALID_REGISTRATION, "The request body is not valid JSON", false, Map.of());
            return null;
        }
        if (body == null || !body.isObject()) {
            api.error(exchange, 400, INVALID_REGISTRATION, "The request body must be a JSON object", false, Map.of());
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
        String query = exchange.getRequestURI().getQuery();
        if (query == null) return false;
        for (String parameter : query.split("&")) {
            if (parameter.equals(REPLACE_EXISTING_CHECKS) || parameter.equals(REPLACE_EXISTING_CHECKS + "=")
                    || parameter.equals(REPLACE_EXISTING_CHECKS + "=true")) {
                return true;
            }
        }
        return false;
    }
}
