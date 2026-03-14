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

package dev.mars.qraft.controller.http.handlers;

import dev.mars.qraft.controller.raft.RaftNode;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * HTTP handler for API information.
 *
 * <p>Endpoint: {@code GET /api/v1/info}
 *
 * <p>Provides information about the generic distributed service API including:
 * API version, available endpoints, controller information, and capabilities.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @version 2.0 (Vert.x reactive)
 * @since 2025-12-11
 */
public class InfoHandler implements Handler<RoutingContext> {

    private static final Logger logger = LoggerFactory.getLogger(InfoHandler.class);
    private static final String API_VERSION = "v1";

    private final RaftNode raftNode;
    private final String qraftVersion;

    public InfoHandler(RaftNode raftNode, String qraftVersion) {
        this.raftNode = raftNode;
        this.qraftVersion = qraftVersion;
    }

    @Override
    public void handle(RoutingContext ctx) {
        logger.debug("API info requested");
        JsonObject info = new JsonObject()
                .put("api", new JsonObject()
                        .put("version", API_VERSION)
                        .put("qraftVersion", qraftVersion)
                        .put("description", "Generic Distributed Service API"))
                .put("controller", new JsonObject()
                        .put("nodeId", raftNode.getNodeId())
                        .put("state", raftNode.getState().toString())
                        .put("isLeader", raftNode.isLeader())
                        .put("currentTerm", raftNode.getCurrentTerm()))
                .put("endpoints", buildEndpoints())
                .put("capabilities", new JsonObject()
                        .put("raftConsensus", true)
                        .put("distributedState", true)
                        .put("replicatedKeyValueState", true)
                        .put("leaderAwareWrites", true)
                        .put("prometheusMetrics", true)
                        .put("healthChecks", true))
                .put("timestamp", Instant.now().toString());

        ctx.json(info);
    }

    private JsonObject buildEndpoints() {
        return new JsonObject()
                .put("health", new JsonArray()
                        .add(endpoint("GET", "/health", "Overall system health"))
                        .add(endpoint("GET", "/health/ready", "Readiness probe"))
                        .add(endpoint("GET", "/health/live", "Liveness probe"))
                        .add(endpoint("GET", "/status", "Simple status check")))
                .put("agents", new JsonArray()
                        .add(endpoint("POST", "/api/v1/agents/register", "Register an agent"))
                        .add(endpoint("GET", "/api/v1/agents", "List registered agents"))
                        .add(endpoint("POST", "/api/v1/agents/heartbeat", "Report agent heartbeat"))
                        .add(endpoint("GET", "/api/v1/agents/:agentId/jobs", "Poll jobs for an agent")))
                .put("transfers", new JsonArray()
                        .add(endpoint("POST", "/api/v1/transfers", "Create a transfer job"))
                        .add(endpoint("GET", "/api/v1/transfers/:jobId", "Get transfer job status")))
                .put("jobs", new JsonArray()
                        .add(endpoint("POST", "/api/v1/jobs/:jobId/status", "Update transfer job assignment status")))
                .put("assignments", new JsonArray()
                        .add(endpoint("POST", "/api/v1/assignments", "Assign a job to an agent"))
                        .add(endpoint("GET", "/api/v1/assignments", "List job assignments"))
                        .add(endpoint("GET", "/api/v1/assignments/:assignmentId", "Get a job assignment"))
                        .add(endpoint("PUT", "/api/v1/assignments/:assignmentId/accept", "Accept an assignment"))
                        .add(endpoint("PUT", "/api/v1/assignments/:assignmentId/reject", "Reject an assignment"))
                        .add(endpoint("PUT", "/api/v1/assignments/:assignmentId/status", "Update assignment status"))
                        .add(endpoint("PUT", "/api/v1/assignments/:assignmentId/cancel", "Cancel an assignment"))
                        .add(endpoint("DELETE", "/api/v1/assignments/:assignmentId", "Delete an assignment")))
                .put("routes", new JsonArray()
                        .add(endpoint("POST", "/api/v1/routes", "Create a route"))
                        .add(endpoint("GET", "/api/v1/routes", "List routes"))
                        .add(endpoint("GET", "/api/v1/routes/:routeId", "Get route details"))
                        .add(endpoint("PUT", "/api/v1/routes/:routeId", "Update a route"))
                        .add(endpoint("DELETE", "/api/v1/routes/:routeId", "Delete a route"))
                        .add(endpoint("PUT", "/api/v1/routes/:routeId/suspend", "Suspend a route"))
                        .add(endpoint("PUT", "/api/v1/routes/:routeId/resume", "Resume a route")))
                .put("state", new JsonArray()
                        .add(endpoint("GET", "/api/v1/state", "List replicated key-value entries"))
                        .add(endpoint("GET", "/api/v1/state/:key", "Get a replicated value"))
                        .add(endpoint("PUT", "/api/v1/state/:key", "Upsert a replicated value"))
                        .add(endpoint("DELETE", "/api/v1/state/:key", "Delete a replicated value")))
                .put("cluster", new JsonArray()
                        .add(endpoint("GET", "/raft/status", "Get cluster status")))
                .put("metrics", new JsonArray()
                        .add(endpoint("GET", "/metrics", "Prometheus metrics")))
                .put("info", new JsonArray()
                        .add(endpoint("GET", "/api/v1/info", "API information and available endpoints")));
    }

    private static JsonObject endpoint(String method, String path, String description) {
        return new JsonObject()
                .put("method", method)
                .put("path", path)
                .put("description", description);
    }
}

