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

package dev.mars.qraft.raft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Test helper that reads Raft, node, and health state through container HTTP APIs for Docker client tests.
 * Every read tolerates an unreachable container by returning {@code null} or {@code -1}, so
 * callers can poll across crashes and restarts.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-27
 * @version 1.0
 */
final class DockerHealthApiHelper {
    static final String CLIENT_ID = "docker-client";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private DockerHealthApiHelper() {
    }

    /** The client's {@code web} instance is discoverable as passing, with both of its checks passing. */
    static boolean passingWithBothChecks(String server) {
        JsonNode entries = get(server + "/v1/health/service/web?passing");
        if (entries == null || entries.size() != 1) return false;
        JsonNode checks = entries.get(0).path("checks");
        return CLIENT_ID.equals(entries.get(0).path("service").path("nodeId").asText())
                && checks.size() == 2
                && "PASSING".equals(checks.get(0).path("status").asText())
                && "PASSING".equals(checks.get(1).path("status").asText());
    }

    /** The single {@code web} health entry, or {@code null} when there is not exactly one or no answer. */
    static JsonNode webEntry(String server) {
        JsonNode entries = get(server + "/v1/health/service/web");
        return entries == null || entries.size() != 1 ? null : entries.get(0);
    }

    /** One check of the single {@code web} entry, or {@code null}. */
    static JsonNode check(String server, String checkId) {
        JsonNode entry = webEntry(server);
        if (entry == null) return null;
        for (JsonNode check : entry.path("checks")) {
            if (checkId.equals(check.path("checkId").asText())) return check;
        }
        return null;
    }

    /** Sequence number of the {@code http} check, or -1. */
    static long httpSequence(String server) {
        JsonNode check = check(server, "http");
        return check == null ? -1 : check.path("sequenceNumber").asLong();
    }

    /** True when any check of the {@code web} entry is expired. */
    static boolean anyCheckExpired(String server) {
        JsonNode entry = webEntry(server);
        if (entry == null) return false;
        for (JsonNode check : entry.path("checks")) {
            if (check.path("expired").asBoolean()) return true;
        }
        return false;
    }

    /** Number of {@code web} instances, or -1 when the server does not answer. */
    static int instanceCount(String server) {
        JsonNode entries = get(server + "/v1/health/service/web");
        return entries == null ? -1 : entries.size();
    }

    /** The client's node entry from {@code /api/v1/clients}, or {@code null}. */
    static JsonNode clientNode(String server) {
        JsonNode clients = get(server + "/api/v1/clients");
        if (clients == null) return null;
        for (JsonNode client : clients) {
            if (CLIENT_ID.equals(client.path("clientId").asText())) return client;
        }
        return null;
    }

    /** {@code snapshotLastIndex} from {@code /raft/status}, or -1. */
    static long snapshotLastIndex(String server) {
        return raftStatus(server, "snapshotLastIndex");
    }

    /** One numeric field of {@code /raft/status}, which the server reads as one consistent view, or -1. */
    static long raftStatus(String server, String field) {
        JsonNode status = get(server + "/raft/status");
        return status == null ? -1 : status.path(field).asLong(-1);
    }

    /** The {@code ?passing} view of {@code web}, or {@code null} when the server does not answer. */
    static JsonNode passingWeb(String server) {
        return get(server + "/v1/health/service/web?passing");
    }

    /**
     * Registers an unrelated service in its own tenant and namespace through {@code server}, which must be
     * the leader, to add one committed log entry. Returns the HTTP status, or -1 without an answer.
     */
    static int registerFiller(String server, int number) {
        String body = """
                {"serviceId":"filler-%d","serviceName":"filler","address":"127.0.0.1","port":%d}
                """.formatted(number, 10_000 + number);
        try {
            return HTTP.send(HttpRequest.newBuilder(URI.create(server + "/v1/client/service/register"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-Qraft-Tenant", "filler").header("X-Qraft-Namespace", "filler")
                    .header("X-Qraft-Node", "filler-node")
                    .PUT(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception unreachable) {
            return -1;
        }
    }

    /** Index of the only reachable server reporting itself leader, or -1 while there is not exactly one. */
    static int leaderIndex(List<String> servers) {
        int leader = -1;
        for (int index = 0; index < servers.size(); index++) {
            JsonNode status = get(servers.get(index) + "/raft/status");
            if (status != null && "LEADER".equals(status.path("state").asText())) {
                if (leader >= 0) return -1;
                leader = index;
            }
        }
        return leader;
    }

    /** HTTP status of a GET, or -1 when the endpoint does not answer. */
    static int status(String uri) {
        try {
            return HTTP.send(HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception unreachable) {
            return -1;
        }
    }

    static JsonNode get(String uri) {
        try {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(uri))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? JSON.readTree(response.body()) : null;
        } catch (Exception unreachable) {
            return null;
        }
    }
}
