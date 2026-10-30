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

package dev.mars.qraft.client.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mars.qraft.client.health.HealthCheckDefinition;
import dev.mars.qraft.client.health.HttpCheck;
import dev.mars.qraft.client.health.ProbeCheck;
import dev.mars.qraft.client.health.TcpCheck;
import dev.mars.qraft.client.health.TtlCheck;
import dev.mars.qraft.common.ServiceDefinition;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static dev.mars.qraft.common.config.JsonSettings.optionalBoolean;
import static dev.mars.qraft.common.config.JsonSettings.optionalInt;
import static dev.mars.qraft.common.config.JsonSettings.optionalLong;
import static dev.mars.qraft.common.config.JsonSettings.rejectUnknown;
import static dev.mars.qraft.common.config.JsonSettings.requiredInt;

/**
 * The JSON form of a service and of its checks, one form wherever a client meets them: the configuration file, the
 * local API's registration bodies, and what the data directory keeps. What is written here reads back as it was, and
 * outlives an upgrade in the data directory, so it changes only with its readers.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-30
 * @version 1.0
 */
public final class DefinitionJson {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final String SERVICE_ID = "serviceId";

    /** A service and the checks registered with it. */
    public record Registration(ServiceDefinition service, List<HealthCheckDefinition> checks) {
        public Registration {
            checks = List.copyOf(checks);
        }
    }

    private DefinitionJson() {
    }

    /**
     * Reads a service registration, by Consul's rules: the ID defaults to the name, and the address to the client's.
     * A service registered this way is enabled; there is no other kind of registered service.
     *
     * @param clientAddress the address of a service that gives none, or {@code null} when it must give one
     * @throws IllegalArgumentException if the registration is not a valid one
     */
    public static Registration parseRegistration(JsonNode body, String clientAddress) {
        rejectUnknown(body, "service registration", "id", "name", "address", "port", "tags", "metadata", "checks");
        String name = requiredText(body, "name");
        ServiceDefinition service = new ServiceDefinition(optionalText(body, "id", name), name,
                optionalText(body, "address", clientAddress), requiredInt(body, "port"),
                stringList(body.get("tags"), "tags"), stringMap(body.get("metadata")), true);
        return new Registration(service, parseChecks(body.get("checks"), service, "checks"));
    }

    /** The service a check registered alone belongs to. */
    public static String serviceIdOf(JsonNode check) {
        return requiredText(check, SERVICE_ID);
    }

    /**
     * Reads a check registered alone, which names its service, for that service.
     *
     * @throws IllegalArgumentException if the check is not a valid one
     */
    public static HealthCheckDefinition parseCheck(JsonNode check, ServiceDefinition service) {
        ObjectNode withoutService = check.deepCopy();
        withoutService.remove(SERVICE_ID);
        return parseCheck(withoutService, requiredText(check, "id"), service, "check");
    }

    /** Reads the checks a service's definition carries; {@code location} says where, in a refusal's message. */
    static List<HealthCheckDefinition> parseChecks(JsonNode checks, ServiceDefinition service, String location) {
        if (checks == null || checks.isNull()) return List.of();
        if (!checks.isArray()) throw new IllegalArgumentException(location + " must be an array");
        List<HealthCheckDefinition> definitions = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode check : checks) {
            if (!check.isObject()) throw new IllegalArgumentException("Each health check must be an object");
            String id = requiredText(check, "id");
            if (!ids.add(id)) {
                throw new IllegalArgumentException("Duplicate health check id " + id + " for service " + service.id());
            }
            definitions.add(parseCheck(check, id, service, location + "[]"));
        }
        return definitions;
    }

    private static HealthCheckDefinition parseCheck(JsonNode check, String id, ServiceDefinition service,
                                                    String location) {
        String type = requiredText(check, "type");
        boolean required = optionalBoolean(check, "required", true);
        Duration deregisterAfter = Duration.ofMillis(optionalLong(check, "deregisterAfterMs", 0));
        return switch (type) {
            case "http" -> {
                rejectUnknown(check, location, "id", "type", "required", "url",
                        "intervalMs", "timeoutMs", "ttlMs", "deregisterAfterMs");
                CheckTiming timing = CheckTiming.parse(check);
                yield new HttpCheck(service.id(), id, checkUrl(requiredText(check, "url")),
                        timing.interval(), timing.timeout(), timing.ttl(), required, deregisterAfter);
            }
            case "tcp" -> {
                rejectUnknown(check, location, "id", "type", "required", "address", "port",
                        "intervalMs", "timeoutMs", "ttlMs", "deregisterAfterMs", "warnAfterMs");
                CheckTiming timing = CheckTiming.parse(check);
                yield new TcpCheck(service.id(), id, optionalText(check, "address", service.address()),
                        optionalInt(check, "port", service.port()),
                        timing.interval(), timing.timeout(), timing.ttl(), required, deregisterAfter,
                        Duration.ofMillis(optionalLong(check, "warnAfterMs", 0)));
            }
            case "ttl" -> {
                rejectUnknown(check, location, "id", "type", "required", "ttlMs", "deregisterAfterMs");
                if (!check.has("ttlMs")) throw new IllegalArgumentException("ttlMs is required for a ttl check");
                yield new TtlCheck(service.id(), id, Duration.ofMillis(optionalLong(check, "ttlMs", 0)), required,
                        deregisterAfter);
            }
            default -> throw new IllegalArgumentException("Unsupported health check type: " + type);
        };
    }

    /** A service as it is written. Its declared checks are not: they follow from the checks that name it. */
    public static ObjectNode write(ServiceDefinition service) {
        ObjectNode written = NODES.objectNode();
        written.put("id", service.id());
        written.put("name", service.name());
        written.put("address", service.address());
        written.put("port", service.port());
        ArrayNode tags = written.putArray("tags");
        service.tags().forEach(tags::add);
        ObjectNode metadata = written.putObject("metadata");
        new TreeMap<>(service.metadata()).forEach(metadata::put);
        return written;
    }

    /** A check as it is written, with its service's ID and every setting spelled out. */
    public static ObjectNode write(HealthCheckDefinition check) {
        ObjectNode written = NODES.objectNode();
        written.put("id", check.checkId());
        written.put(SERVICE_ID, check.serviceId());
        switch (check) {
            case HttpCheck http -> {
                written.put("type", "http");
                written.put("required", http.required());
                written.put("url", http.url().toString());
                writeTiming(written, http);
            }
            case TcpCheck tcp -> {
                written.put("type", "tcp");
                written.put("required", tcp.required());
                written.put("address", tcp.host());
                written.put("port", tcp.port());
                written.put("warnAfterMs", tcp.warnAfter().toMillis());
                writeTiming(written, tcp);
            }
            case TtlCheck ttl -> {
                written.put("type", "ttl");
                written.put("required", ttl.required());
                written.put("ttlMs", ttl.ttl().toMillis());
            }
        }
        written.put("deregisterAfterMs", check.deregisterAfter().toMillis());
        return written;
    }

    private static void writeTiming(ObjectNode written, ProbeCheck check) {
        written.put("intervalMs", check.interval().toMillis());
        written.put("timeoutMs", check.timeout().toMillis());
        written.put("ttlMs", check.ttl().toMillis());
    }

    private static URI checkUrl(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("Malformed health check url: " + value, error);
        }
    }

    /** Probe timing: interval defaults to 10s, timeout to min(2s, interval), and ttl to three intervals. */
    private record CheckTiming(Duration interval, Duration timeout, Duration ttl) {
        static CheckTiming parse(JsonNode check) {
            long interval = optionalLong(check, "intervalMs", 10_000);
            if (interval < 1) throw new IllegalArgumentException("intervalMs must be positive");
            if (interval > Long.MAX_VALUE / 3) throw new IllegalArgumentException("intervalMs is too large");
            long timeout = optionalLong(check, "timeoutMs", Math.min(2_000, interval));
            long ttl = optionalLong(check, "ttlMs", interval * 3);
            return new CheckTiming(Duration.ofMillis(interval), Duration.ofMillis(timeout), Duration.ofMillis(ttl));
        }
    }

    static List<String> stringList(JsonNode node, String field) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) throw new IllegalArgumentException(field + " must be an array");
        List<String> values = new ArrayList<>();
        node.forEach(value -> {
            if (!value.isTextual()) throw new IllegalArgumentException(field + " entries must be strings");
            values.add(value.textValue());
        });
        return values;
    }

    static Map<String, String> stringMap(JsonNode node) {
        if (node == null || node.isNull()) return Map.of();
        if (!node.isObject()) throw new IllegalArgumentException("metadata must be an object");
        Map<String, String> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) {
                throw new IllegalArgumentException("metadata values must be strings");
            }
            values.put(entry.getKey(), entry.getValue().textValue());
        });
        return values;
    }

    static String requiredText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string");
        }
        return value.textValue().trim();
    }

    static String optionalText(JsonNode parent, String field, String fallback) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) return fallback;
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string");
        }
        return value.textValue().trim();
    }
}
