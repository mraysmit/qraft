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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mars.qraft.client.config.DefinitionJson;
import dev.mars.qraft.client.health.HealthCheckDefinition;
import dev.mars.qraft.common.ServiceDefinition;
import dev.mars.qraft.common.config.JsonSettings;
import dev.mars.qraft.common.storage.DurableFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What the local API registered and put in maintenance, kept in {@value #FILE_NAME} in the client's data directory, as Consul's agent keeps
 * what its API registers in its data directory. Each write replaces the file atomically.
 *
 * <p>A file that cannot be read stops the start and is not replaced, because replacing it would lose the
 * registrations in it. Consul's agent logs such a file and starts without it; a client is stricter, so that an
 * application's service does not silently stop being discoverable.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-31
 * @version 1.0
 */
public final class RegistrationStore {
    static final String FILE_NAME = "registrations.json";
    private static final int VERSION = 1;
    private static final String MAINTENANCE = "maintenance";
    private static final String NODE = "node";
    private static final String SERVICES = "services";
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * What is kept: services the API registered, checks it registered, whichever services those are for, and the
     * reasons for maintenance: the node's, or {@code null} when it is not in maintenance, and by service ID.
     */
    public record Kept(List<ServiceDefinition> services, List<HealthCheckDefinition> checks, String nodeMaintenance,
                       Map<String, String> serviceMaintenance) {
        public Kept {
            services = List.copyOf(services);
            checks = List.copyOf(checks);
            serviceMaintenance = Map.copyOf(serviceMaintenance);
        }

        public Kept(List<ServiceDefinition> services, List<HealthCheckDefinition> checks) {
            this(services, checks, null, Map.of());
        }
    }

    private final Path file;

    public RegistrationStore(Path directory) {
        this.file = directory.resolve(FILE_NAME);
    }

    /**
     * What is kept, or nothing if there is no file.
     *
     * @throws IllegalStateException if the file exists but cannot be read
     */
    public Kept read() {
        if (!Files.exists(file)) return new Kept(List.of(), List.of());
        try {
            JsonNode root = JSON.readTree(Files.readAllBytes(file));
            if (root == null || !root.isObject()) throw new IllegalArgumentException("it is not a JSON object");
            if (root.path("version").asInt() != VERSION) {
                throw new IllegalArgumentException("its version is not " + VERSION + ": " + root.path("version"));
            }
            List<ServiceDefinition> services = new ArrayList<>();
            for (JsonNode service : array(root, "services")) {
                services.add(DefinitionJson.parseRegistration(service, null).service());
            }
            List<HealthCheckDefinition> checks = new ArrayList<>();
            for (JsonNode check : array(root, "checks")) {
                checks.add(DefinitionJson.readWrittenCheck(check));
            }
            JsonNode maintenance = root.get(MAINTENANCE);
            return maintenance == null ? new Kept(services, checks)
                    : new Kept(services, checks, nodeMaintenance(maintenance), serviceMaintenance(maintenance));
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalStateException("The registrations kept in " + file + " cannot be read: "
                    + failure.getMessage() + ". The file is not replaced, because that would lose them; restore it, "
                    + "or remove it to start without them", failure);
        }
    }

    /** Replaces what is kept. When this fails, what was kept is untouched. */
    public void write(Kept kept) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("version", VERSION);
        ArrayNode services = root.putArray("services");
        kept.services().forEach(service -> services.add(DefinitionJson.write(service)));
        ArrayNode checks = root.putArray("checks");
        kept.checks().forEach(check -> checks.add(DefinitionJson.write(check)));
        if (kept.nodeMaintenance() != null || !kept.serviceMaintenance().isEmpty()) {
            ObjectNode maintenance = root.putObject(MAINTENANCE);
            if (kept.nodeMaintenance() != null) maintenance.put(NODE, kept.nodeMaintenance());
            ObjectNode reasons = maintenance.putObject(SERVICES);
            new TreeMap<>(kept.serviceMaintenance()).forEach(reasons::put);
        }
        DurableFile.write(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String nodeMaintenance(JsonNode maintenance) {
        JsonSettings.rejectUnknown(requireObject(maintenance, MAINTENANCE), MAINTENANCE, NODE, SERVICES);
        return maintenance.has(NODE) ? reason(maintenance.get(NODE)) : null;
    }

    private static Map<String, String> serviceMaintenance(JsonNode maintenance) {
        if (!maintenance.has(SERVICES)) return Map.of();
        Map<String, String> reasons = new LinkedHashMap<>();
        requireObject(maintenance.get(SERVICES), SERVICES).fields()
                .forEachRemaining(entry -> reasons.put(entry.getKey(), reason(entry.getValue())));
        return reasons;
    }

    private static JsonNode requireObject(JsonNode value, String field) {
        if (!value.isObject()) throw new IllegalArgumentException(field + " is not an object");
        return value;
    }

    private static String reason(JsonNode value) {
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("a reason for maintenance is not a non-blank string: " + value);
        }
        return value.textValue();
    }

    private static JsonNode array(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isArray()) throw new IllegalArgumentException(field + " is not an array");
        return value;
    }
}
