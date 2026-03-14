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

import dev.mars.qraft.controller.http.ErrorCode;
import dev.mars.qraft.controller.http.QraftApiException;
import dev.mars.qraft.controller.raft.RaftNode;
import dev.mars.qraft.controller.state.CommandResult;
import dev.mars.qraft.controller.state.DistributedStateRaftCommand;
import dev.mars.qraft.controller.state.GenericStateStore;
import dev.mars.qraft.distributedstate.DistributedStateCommand;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;

/**
 * Generic distributed key-value state API backed by Raft consensus.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET /api/v1/state - list all replicated key-value entries</li>
 *   <li>GET /api/v1/state/:key - get a single replicated value</li>
 *   <li>PUT /api/v1/state/:key - upsert a replicated value</li>
 *   <li>DELETE /api/v1/state/:key - delete a replicated value</li>
 * </ul>
 */
public class DistributedStateHandler {

    private static final Logger logger = LoggerFactory.getLogger(DistributedStateHandler.class);

    private final RaftNode raftNode;
    private final GenericStateStore stateStore;

    public DistributedStateHandler(RaftNode raftNode, GenericStateStore stateStore) {
        this.raftNode = raftNode;
        this.stateStore = stateStore;
    }

    public Handler<RoutingContext> handleList() {
        return ctx -> {
            Map<String, String> entries = stateStore.getMetadata();
            ctx.json(new JsonObject()
                    .put("count", entries.size())
                    .put("entries", entries)
                    .put("timestamp", Instant.now().toString()));
        };
    }

    public Handler<RoutingContext> handleGet() {
        return ctx -> {
            String key = ctx.pathParam("key");
            String value = stateStore.getMetadata().get(key);
            if (value == null) {
                throw QraftApiException.notFound(ErrorCode.NOT_FOUND, "state key '" + key + "'");
            }

            ctx.json(new JsonObject()
                    .put("key", key)
                    .put("value", value)
                    .put("timestamp", Instant.now().toString()));
        };
    }

    public Handler<RoutingContext> handlePut() {
        return ctx -> {
            String key = ctx.pathParam("key");
            JsonObject body = ctx.body().asJsonObject();
            if (body == null || !body.containsKey("value")) {
                throw QraftApiException.badRequest(ErrorCode.MISSING_REQUIRED_FIELD, "value");
            }

            String value = String.valueOf(body.getValue("value"));
        DistributedStateRaftCommand command = new DistributedStateRaftCommand(
            DistributedStateCommand.put(key, value));

            raftNode.submitCommand(command)
                    .onSuccess(result -> {
                        if (result instanceof CommandResult.Success<?>) {
                            logger.debug("Replicated state upsert committed: key={}", key);
                            ctx.response().setStatusCode(200);
                            ctx.json(new JsonObject()
                                    .put("success", true)
                                    .put("key", key)
                                    .put("value", value));
                            return;
                        }
                        ctx.fail(QraftApiException.internal(
                                ErrorCode.RAFT_COMMIT_FAILED,
                                null,
                                "Unexpected command result for key: " + key));
                    })
                    .onFailure(ctx::fail);
        };
    }

    public Handler<RoutingContext> handleDelete() {
        return ctx -> {
            String key = ctx.pathParam("key");
            DistributedStateRaftCommand command = new DistributedStateRaftCommand(
                    DistributedStateCommand.delete(key));

            raftNode.submitCommand(command)
                    .onSuccess(result -> {
                        if (result instanceof CommandResult.NotFound<?>) {
                            ctx.fail(QraftApiException.notFound(ErrorCode.NOT_FOUND, "state key '" + key + "'"));
                            return;
                        }

                        if (result instanceof CommandResult.Success<?> || result instanceof CommandResult.NoOp<?>) {
                            logger.debug("Replicated state delete committed: key={}", key);
                            ctx.json(new JsonObject()
                                    .put("success", true)
                                    .put("key", key));
                            return;
                        }

                        ctx.fail(QraftApiException.internal(
                                ErrorCode.RAFT_COMMIT_FAILED,
                                null,
                                "Unexpected command result for key: " + key));
                    })
                    .onFailure(ctx::fail);
        };
    }
}
