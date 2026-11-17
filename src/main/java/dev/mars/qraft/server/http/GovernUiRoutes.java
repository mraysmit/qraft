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

package dev.mars.qraft.server.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import dev.mars.qraft.common.acl.AclAccess;
import dev.mars.qraft.common.acl.AclRequest;
import dev.mars.qraft.common.acl.AclResource;
import dev.mars.qraft.state.catalog.ServiceInstance;
import dev.mars.qraft.server.acl.AclPrincipal;
import dev.mars.qraft.state.kv.KvEntry;
import dev.mars.qraft.state.kv.KvScope;
import dev.mars.qraft.state.kv.KvState;
import dev.mars.qraft.state.tenancy.Namespace;
import dev.mars.qraft.state.tenancy.Tenant;
import dev.mars.qraft.state.tenancy.TenancyState;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The administrative interface's governance reads (UI-6 Tenants, Namespaces and Audit task list, Phase 0):
 * {@code GET /v1/internal/ui/scopes} answers every tenant, with its namespaces, each with what it holds: its services,
 * instances and keys, and the keys held in it with the sessions holding them (decision 2). It needs
 * {@code operator:read}, as the tenancy reads do, and takes the read options, answering the applied index.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-17
 * @version 1.0
 */
final class GovernUiRoutes {
    static final String SCOPES = "/v1/internal/ui/scopes";
    static final String AUDIT = "/v1/internal/ui/audit";
    private static final AclRequest OPERATOR_READ = AclRequest.single(AclResource.OPERATOR, AclAccess.READ);
    private static final String REQUEST = "audit.http_request";
    /** A line of the audit file that is not a JSON object, answered as what it is. */
    private static final String UNREADABLE = "audit.unreadable_line";
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1000;
    /** Each exact filter's parameter, and the field of a record it matches. */
    private static final String[][] EXACT = {{"accessor", "AccessorId"}, {"requestId", "RequestId"},
            {"method", "Method"}, {"tenant", "Tenant"}, {"namespace", "Namespace"}};
    private static final Set<String> AUDIT_PARAMETERS = Set.of("limit", "accessor", "requestId", "method", "route",
            "tenant", "namespace", "result", "before", "from");

    private final HttpApiServer server;

    GovernUiRoutes(HttpApiServer server) {
        this.server = server;
    }

    void scopes(HttpExchange exchange) throws IOException {
        if (!CoordinationUiRoutes.routed(server, exchange, SCOPES)) return;
        if (CoordinationUiRoutes.query(server, exchange, Set.of()) == null) return;
        AclPrincipal principal = server.principal(exchange);
        if (principal == null) return;
        if (server.refused(exchange, principal, OPERATOR_READ)) return;
        if (!server.readyToRead(exchange, HttpApiServer.ReadIndex.APPLIED)) return;
        server.setAppliedIndex(exchange);
        TenancyState tenancy = server.stateStore.getTenancy();
        List<ServiceInstance> instances = server.stateStore.getServiceCatalog().instances();
        KvState kv = server.stateStore.getKv();
        List<KvEntry> held = kv.held();
        List<Map<String, Object>> tenants = tenancy.tenants().stream().map(tenant -> {
            Map<String, Object> answer = identity(tenant.name(), tenant);
            answer.put("Namespaces", tenancy.namespaces(tenant.name()).stream()
                    .map(namespace -> holdings(namespace, instances, kv, held)).toList());
            return answer;
        }).toList();
        server.respondJson(exchange, 200, Map.of("Tenants", tenants));
    }

    void audit(HttpExchange exchange) throws IOException {
        if (!CoordinationUiRoutes.routed(server, exchange, AUDIT)) return;
        Map<String, String> query = CoordinationUiRoutes.query(server, exchange, AUDIT_PARAMETERS);
        if (query == null) return;
        AuditFilter filter;
        try {
            filter = AuditFilter.of(query);
        } catch (IllegalArgumentException invalid) {
            server.respondError(exchange, 400, "invalid_query", invalid.getMessage(), false);
            return;
        }
        AclPrincipal principal = server.principal(exchange);
        if (principal == null) return;
        if (server.refused(exchange, principal, OPERATOR_READ)) return;
        Path file = server.auditFile();
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("Configured", file != null);
        answer.put("File", file == null ? null : file.toString());
        List<Map<String, Object>> records = new ArrayList<>();
        AuditFileReader.Scan scan = file == null ? new AuditFileReader.Scan(0, 0, true, -1)
                : AuditFileReader.readBackwards(file, filter.from(), server.auditScanLimit(), line -> {
                    if (records.size() >= filter.limit()) return false;
                    Map<String, Object> entry = entryOf(line);
                    if (filter.matches(entry)) records.add(entry);
                    return true;
                });
        answer.put("Size", scan.size());
        answer.put("Scanned", scan.scanned());
        answer.put("Complete", scan.complete());
        // Where the next page goes on from: a time would rescan what this read covered.
        answer.put("Next", scan.next() < 0 ? null : scan.next());
        answer.put("Records", records);
        server.respondJson(exchange, 200, answer);
    }

    /** An audit line as the read answers it; a line that is not a JSON object is one that could not be read. */
    private Map<String, Object> entryOf(String line) {
        Map<String, Object> entry = new LinkedHashMap<>();
        JsonNode node;
        try {
            node = server.objectMapper.readTree(line);
        } catch (IOException _) {
            node = null;
        }
        if (node == null || !node.isObject()) {
            entry.put("Type", UNREADABLE);
            return entry;
        }
        entry.put("Type", text(node, "type"));
        if (!REQUEST.equals(text(node, "type"))) return entry;
        entry.put("Timestamp", text(node, "timestamp"));
        entry.put("Node", text(node, "node"));
        entry.put("AccessorId", text(node, "accessorId"));
        entry.put("Method", text(node, "method"));
        entry.put("Route", text(node, "route"));
        entry.put("Status", node.path("status").isInt() ? node.path("status").asInt() : null);
        entry.put("Tenant", text(node, "tenant"));
        entry.put("Namespace", text(node, "namespace"));
        entry.put("RequestId", text(node, "requestId"));
        return entry;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /**
     * The audit read's filters (decision 5). A record that is not a request, a lost stretch or a line that could not be
     * read, always matches: what it stands for may have.
     */
    private record AuditFilter(int limit, Map<String, String> exact, String route, String result, Instant before,
                               long from) {
        static AuditFilter of(Map<String, String> query) {
            Map<String, String> exact = new LinkedHashMap<>();
            for (String[] field : EXACT) {
                if (!query.containsKey(field[0])) continue;
                String value = query.get(field[0]);
                if (value.isBlank()) throw new IllegalArgumentException(field[0] + " must not be blank");
                exact.put(field[1], field[1].equals("Method") ? value.toUpperCase(java.util.Locale.ROOT) : value);
            }
            String result = query.get("result");
            if (result != null && !Set.of("allowed", "denied", "failed").contains(result)) {
                throw new IllegalArgumentException("result is allowed, denied or failed: " + result);
            }
            String route = query.get("route");
            if (route != null && route.isBlank()) throw new IllegalArgumentException("route must not be blank");
            return new AuditFilter(limitOf(query.get("limit")), exact, route, result, beforeOf(query.get("before")),
                    fromOf(query.get("from")));
        }

        /** The offset a read goes on from, the end of the file when none is given. */
        private static long fromOf(String given) {
            if (given == null) return Long.MAX_VALUE;
            long from;
            try {
                from = Long.parseLong(given);
            } catch (NumberFormatException _) {
                from = -1;
            }
            if (from < 0) throw new IllegalArgumentException("from is an offset in the file, as Next gives it: " + given);
            return from;
        }

        private static int limitOf(String given) {
            if (given == null) return DEFAULT_LIMIT;
            int limit;
            try {
                limit = Integer.parseInt(given);
            } catch (NumberFormatException _) {
                limit = -1;
            }
            if (limit < 1 || limit > MAX_LIMIT) {
                throw new IllegalArgumentException("limit is from 1 to " + MAX_LIMIT + ": " + given);
            }
            return limit;
        }

        private static Instant beforeOf(String given) {
            if (given == null) return null;
            try {
                return Instant.parse(given);
            } catch (java.time.format.DateTimeParseException _) {
                throw new IllegalArgumentException("before is an instant, such as 2026-11-17T10:00:00Z: " + given);
            }
        }

        boolean matches(Map<String, Object> entry) {
            if (!REQUEST.equals(entry.get("Type"))) return true;
            for (Map.Entry<String, String> wanted : exact.entrySet()) {
                if (!wanted.getValue().equals(entry.get(wanted.getKey()))) return false;
            }
            if (route != null && !(entry.get("Route") instanceof String named && named.startsWith(route))) return false;
            if (result != null && !result.equals(resultOf(entry.get("Status")))) return false;
            return before == null || entry.get("Timestamp") instanceof String at && isBefore(at, before);
        }

        private static String resultOf(Object status) {
            if (!(status instanceof Integer code)) return null;
            if (code == 403) return "denied";
            return code >= 500 ? "failed" : "allowed";
        }

        private static boolean isBefore(String at, Instant before) {
            try {
                return Instant.parse(at).isBefore(before);
            } catch (java.time.format.DateTimeParseException _) {
                return false;
            }
        }
    }

    private static Map<String, Object> identity(String name, Tenant tenant) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("Name", name);
        answer.put("Description", tenant.description());
        answer.put("CreateIndex", tenant.createIndex());
        answer.put("ModifyIndex", tenant.modifyIndex());
        return answer;
    }

    /** What a namespace holds: its services, instances and keys, and its held keys with their sessions. */
    private static Map<String, Object> holdings(Namespace namespace, List<ServiceInstance> instances, KvState kv,
                                                List<KvEntry> held) {
        List<ServiceInstance> mine = instances.stream().filter(instance -> instance.tenantId().equals(namespace.tenant())
                && instance.namespace().equals(namespace.name())).toList();
        List<KvEntry> locked = held.stream().filter(entry -> entry.tenant().equals(namespace.tenant())
                && entry.namespace().equals(namespace.name())).toList();
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("Name", namespace.name());
        answer.put("Description", namespace.description());
        answer.put("CreateIndex", namespace.createIndex());
        answer.put("ModifyIndex", namespace.modifyIndex());
        answer.put("Services", mine.stream().map(ServiceInstance::serviceName).distinct().count());
        answer.put("Instances", mine.size());
        answer.put("Keys", kv.list(new KvScope(namespace.tenant(), namespace.name()), "").size());
        answer.put("HeldKeys", locked.size());
        answer.put("Sessions", locked.stream().map(KvEntry::session).collect(java.util.stream.Collectors
                .toCollection(TreeSet::new)));
        return answer;
    }
}
