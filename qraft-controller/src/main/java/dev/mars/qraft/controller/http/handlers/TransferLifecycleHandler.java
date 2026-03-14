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
import dev.mars.qraft.controller.state.JobAssignmentCommand;
import dev.mars.qraft.controller.state.JobQueueCommand;
import dev.mars.qraft.controller.state.QraftStateStore;
import dev.mars.qraft.core.JobAssignment;
import dev.mars.qraft.core.JobAssignmentStatus;
import dev.mars.qraft.core.QueuedJob;
import dev.mars.qraft.core.TransferJob;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP handler for legacy transfer + job status endpoints used by controller tests.
 */
public class TransferLifecycleHandler {

    private static final Logger logger = LoggerFactory.getLogger(TransferLifecycleHandler.class);

    private final RaftNode raftNode;
    private final QraftStateStore stateStore;

    public TransferLifecycleHandler(RaftNode raftNode, QraftStateStore stateStore) {
        this.raftNode = raftNode;
        this.stateStore = stateStore;
    }

    /**
     * Handles POST /api/v1/transfers.
     */
    public Handler<RoutingContext> handleCreateTransfer() {
        return ctx -> {
            try {
                JsonObject body = ctx.body().asJsonObject();
                if (body == null) {
                    throw new IllegalArgumentException("Request body is required");
                }

                String jobId = requireField(body, "jobId");
                String sourceUriRaw = requireField(body, "sourceUri");
                String destinationPath = requireField(body, "destinationPath");

                if (stateStore.findQueuedJob(jobId).isPresent()) {
                    throw QraftApiException.conflict(ErrorCode.TRANSFER_DUPLICATE, jobId);
                }

                long totalBytes = body.getLong("totalBytes", -1L);
                String description = body.getString("description");

                TransferJob transferJob = TransferJob.create(
                        jobId,
                        URI.create(sourceUriRaw),
                        destinationPath,
                        totalBytes,
                        description);

                QueuedJob queuedJob = new QueuedJob.Builder()
                        .transferJob(transferJob)
                        .build();

                raftNode.submitCommand(JobQueueCommand.enqueue(queuedJob))
                        .onSuccess(result -> {
                            if (result instanceof CommandResult.NotFound<?> nf) {
                                ctx.fail(QraftApiException.notFound(ErrorCode.TRANSFER_NOT_FOUND, nf.id()));
                                return;
                            }
                            ctx.response().setStatusCode(201);
                            ctx.json(new JsonObject()
                                    .put("success", true)
                                    .put("jobId", jobId));
                        })
                        .onFailure(ctx::fail);
            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    /**
     * Handles GET /api/v1/transfers/:jobId.
     */
    public Handler<RoutingContext> handleGetTransfer() {
        return ctx -> {
            String jobId = ctx.pathParam("jobId");

            QueuedJob queuedJob = stateStore.findQueuedJob(jobId)
                    .orElseThrow(() -> QraftApiException.notFound(ErrorCode.TRANSFER_NOT_FOUND, jobId));

            JsonObject response = new JsonObject()
                    .put("jobId", jobId)
                    .put("sourceUri", queuedJob.getTransferJob().getRequest().getSourceUri().toString())
                    .put("destinationPath", queuedJob.getTransferJob().getRequest().getDestinationUri().toString())
                    .put("bytesTransferred", queuedJob.getTransferJob().getBytesTransferred())
                    .put("totalBytes", queuedJob.getTransferJob().getTotalBytes());

            String assignmentStatus = computeMostRelevantAssignmentStatus(jobId)
                    .map(Enum::name)
                    .orElse(queuedJob.getTransferJob().getStatus().name());

            response.put("status", assignmentStatus);
            ctx.json(response);
        };
    }

    /**
     * Handles POST /api/v1/jobs/:jobId/status.
     */
    public Handler<RoutingContext> handleUpdateJobStatus() {
        return ctx -> {
            try {
                String jobId = ctx.pathParam("jobId");
                JsonObject body = ctx.body().asJsonObject();
                if (body == null) {
                    throw new IllegalArgumentException("Request body is required");
                }

                String agentId = requireField(body, "agentId");
                String newStatusRaw = requireField(body, "status");

                JobAssignmentStatus newStatus;
                try {
                    newStatus = JobAssignmentStatus.valueOf(newStatusRaw);
                } catch (IllegalArgumentException e) {
                    throw QraftApiException.badRequest(ErrorCode.VALIDATION_ERROR,
                            "Invalid status: " + newStatusRaw);
                }

                Optional<Map.Entry<String, JobAssignment>> assignmentEntry = stateStore.getJobAssignments().entrySet().stream()
                        .filter(entry -> jobId.equals(entry.getValue().getJobId()))
                        .filter(entry -> agentId.equals(entry.getValue().getAgentId()))
                        .max(Comparator.comparing(entry -> assignmentActivityTimestamp(entry.getValue())));

                if (assignmentEntry.isEmpty()) {
                    throw QraftApiException.notFound(ErrorCode.ASSIGNMENT_NOT_FOUND, jobId);
                }

                String assignmentId = assignmentEntry.get().getKey();
                JobAssignment current = assignmentEntry.get().getValue();

                if (!current.getStatus().canTransitionTo(newStatus)) {
                    throw QraftApiException.conflict(ErrorCode.ASSIGNMENT_STATE_CONFLICT,
                            assignmentId, current.getStatus().name(), "transition to " + newStatus.name());
                }

                JobAssignmentCommand statusCommand = JobAssignmentCommand.updateStatus(
                        assignmentId, current.getStatus(), newStatus);

                raftNode.submitCommand(statusCommand)
                        .compose(result -> {
                            if (result instanceof CommandResult.CasMismatch<?>) {
                                return Future.failedFuture(QraftApiException.conflict(
                                        ErrorCode.ASSIGNMENT_STATE_CONFLICT,
                                        assignmentId,
                                        current.getStatus().name(),
                                        "update (concurrent modification)"));
                            }
                            if (result instanceof CommandResult.NotFound<?> nf) {
                                return Future.failedFuture(QraftApiException.notFound(
                                        ErrorCode.ASSIGNMENT_NOT_FOUND,
                                        nf.id()));
                            }
                            return updateTransferProgress(jobId, newStatus, body);
                        })
                        .onSuccess(v -> ctx.json(new JsonObject()
                                .put("success", true)
                                .put("jobId", jobId)
                                .put("status", newStatus.name())))
                        .onFailure(ctx::fail);

            } catch (Exception e) {
                ctx.fail(e);
            }
        };
    }

    private Future<Void> updateTransferProgress(String jobId, JobAssignmentStatus newStatus, JsonObject body) {
        Optional<QueuedJob> queuedJobOpt = stateStore.findQueuedJob(jobId);
        if (queuedJobOpt.isEmpty()) {
            return Future.succeededFuture();
        }

        QueuedJob queuedJob = queuedJobOpt.get();
        TransferJob transferJob = queuedJob.getTransferJob();

        Long bytesTransferred = body.getLong("bytesTransferred");
        if (bytesTransferred != null && bytesTransferred >= 0) {
            transferJob.updateProgress(bytesTransferred);
        }

        switch (newStatus) {
            case ACCEPTED, IN_PROGRESS -> {
                if (transferJob.getStatus() == dev.mars.qraft.core.TransferStatus.PENDING
                        || transferJob.getStatus() == dev.mars.qraft.core.TransferStatus.PAUSED) {
                    transferJob.start();
                }
            }
            case COMPLETED -> transferJob.complete(null);
            case FAILED -> transferJob.fail(body.getString("error", "Agent reported failure"), null);
            case CANCELLED -> transferJob.cancel();
            default -> {
                // No transfer-level status change required.
            }
        }

        return raftNode.submitCommand(JobQueueCommand.updateRequirements(queuedJob)).mapEmpty();
    }

    private Optional<JobAssignmentStatus> computeMostRelevantAssignmentStatus(String jobId) {
        return stateStore.getJobAssignments().values().stream()
                .filter(assignment -> jobId.equals(assignment.getJobId()))
                .max(Comparator
                        .comparingInt(this::statusPriority)
                        .thenComparing(this::assignmentActivityTimestamp))
                .map(JobAssignment::getStatus);
    }

    private int statusPriority(JobAssignment assignment) {
        return switch (assignment.getStatus()) {
            case ASSIGNED -> 1;
            case ACCEPTED -> 2;
            case IN_PROGRESS -> 3;
            case COMPLETED, FAILED, REJECTED, TIMEOUT, CANCELLED -> 4;
        };
    }

    private long assignmentActivityTimestamp(JobAssignment assignment) {
        if (assignment.getCompletedAt() != null) {
            return assignment.getCompletedAt().toEpochMilli();
        }
        if (assignment.getStartedAt() != null) {
            return assignment.getStartedAt().toEpochMilli();
        }
        if (assignment.getAcceptedAt() != null) {
            return assignment.getAcceptedAt().toEpochMilli();
        }
        return assignment.getAssignedAt().toEpochMilli();
    }

    private String requireField(JsonObject body, String field) {
        String value = body.getString(field);
        if (value == null || value.isBlank()) {
            throw QraftApiException.badRequest(ErrorCode.MISSING_REQUIRED_FIELD, field);
        }
        return value;
    }
}
