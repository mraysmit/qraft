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

import dev.mars.qraft.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.raft.grpc.AppendEntriesResponse;
import dev.mars.qraft.raft.grpc.DescribeRequest;
import dev.mars.qraft.raft.grpc.DescribeResponse;
import dev.mars.qraft.raft.grpc.InstallSnapshotRequest;
import dev.mars.qraft.raft.grpc.InstallSnapshotResponse;
import dev.mars.qraft.raft.grpc.JoinRequest;
import dev.mars.qraft.raft.grpc.MembershipResponse;
import dev.mars.qraft.raft.grpc.PreVoteRequest;
import dev.mars.qraft.raft.grpc.PreVoteResponse;
import dev.mars.qraft.raft.grpc.RemoveServerRequest;
import dev.mars.qraft.raft.grpc.RaftServiceGrpc;
import dev.mars.qraft.raft.grpc.VoteRequest;
import dev.mars.qraft.raft.grpc.VoteResponse;
import io.grpc.BindableService;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import dev.mars.qraft.common.async.BlockingTaskExecutor;
import dev.mars.qraft.common.concurrent.Completions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * gRPC server implementation for Raft inter-node communication.
 * Handles incoming RequestVote and AppendEntries RPC calls from peer nodes.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @version 1.0
 * @since 2026-01-08
 */
public class GrpcRaftServer {

    private static final Logger logger = LoggerFactory.getLogger(GrpcRaftServer.class);
    private static final Tracer tracer = GlobalOpenTelemetry.getTracer("qraft-raft");
    private static final Metadata.Key<String> REQUEST_ID_HEADER =
            Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER);
    private static final Context.Key<String> REQUEST_ID_CONTEXT = Context.key("qraft-request-id");
    /**
     * The shortest interval at which a peer may ping a connection, open call or not. It is below the transport's
     * {@link GrpcRaftTransport#KEEPALIVE_TIME}; gRPC's default of five minutes, with no pings on a connection without
     * a call, would close the transport's connections as sending too many pings.
     */
    static final Duration PERMITTED_KEEPALIVE_TIME = Duration.ofSeconds(5);
    private static final Context.Key<io.opentelemetry.context.Context> TELEMETRY_CONTEXT =
            Context.key("qraft-telemetry-context");
    private static final TextMapGetter<Metadata> METADATA_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Metadata carrier) {
            return carrier.keys();
        }

        @Override
        public String get(Metadata carrier, String key) {
            return carrier == null ? null : carrier.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER));
        }
    };

    private final BlockingTaskExecutor blockingTasks;
    private final int port;
    private final RaftNode raftNode;
    private final MembershipService membership;
    private final BindableService[] extraServices;
    private volatile Server server;

    public GrpcRaftServer(BlockingTaskExecutor blockingTasks, int port, RaftNode raftNode) {
        this(blockingTasks, port, raftNode, new BindableService[0]);
    }

    public GrpcRaftServer(BlockingTaskExecutor blockingTasks, int port, RaftNode raftNode, BindableService... extraServices) {
        this(blockingTasks, port, raftNode, null, extraServices);
    }

    /**
     * A server that also serves joins and removals through {@code membership}; without it, both are answered
     * {@code NO_LEADER}.
     */
    public GrpcRaftServer(BlockingTaskExecutor blockingTasks, int port, RaftNode raftNode, MembershipService membership,
                          BindableService... extraServices) {
        this.blockingTasks = blockingTasks;
        this.port = port;
        this.raftNode = raftNode;
        this.membership = membership;
        this.extraServices = extraServices != null ? extraServices : new BindableService[0];
    }

    /**
     * Start the gRPC server.
     * 
     * @return Future that completes when server is started
     */
    public CompletableFuture<Void> start() {
        CompletableFuture<Void> promise = new CompletableFuture<>();

        blockingTasks.execute(() -> {
            try {
            ServerBuilder<?> builder = ServerBuilder.forPort(port)
                .permitKeepAliveTime(PERMITTED_KEEPALIVE_TIME.toNanos(), TimeUnit.NANOSECONDS)
                .permitKeepAliveWithoutCalls(true)
                .intercept(new CorrelationIdServerInterceptor())
                .addService(new RaftServiceImpl());

            for (BindableService service : extraServices) {
                builder.addService(service);
            }

            server = builder.build().start();
                logger.info("gRPC Raft server started on port {}", server.getPort());
                return null;
            } catch (IOException e) {
                throw new RuntimeException("Failed to start gRPC server on port " + port, e);
            }
        }).whenComplete(Completions.outcome(v -> promise.complete(null), promise::completeExceptionally));

        return promise;
    }

    /**
     * The port this server is listening on. It differs from the configured port when that is 0, which
     * asks the system for any free port.
     *
     * @throws IllegalStateException when the server is not running
     */
    public int port() {
        Server current = server;
        if (current == null || current.isShutdown()) throw new IllegalStateException("the gRPC server is not running");
        return current.getPort();
    }

    /**
     * Stop the gRPC server gracefully.
     * 
     * @return Future that completes when server is stopped
     */
    public CompletableFuture<Void> stop() {
        CompletableFuture<Void> promise = new CompletableFuture<>();

        if (server == null) {
            promise.complete(null);
            return promise;
        }

        blockingTasks.execute(() -> {
            try {
                server.shutdown();
                if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                    server.shutdownNow();
                    if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                        logger.warn("gRPC server did not terminate cleanly");
                    }
                }
                logger.info("gRPC Raft server stopped");
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                server.shutdownNow();
                throw new RuntimeException("Interrupted while stopping gRPC server", e);
            }
        }).whenComplete(Completions.outcome(v -> promise.complete(null), promise::completeExceptionally));

        return promise;
    }

    /**
     * Implementation of the RaftService gRPC service.
     * Delegates all calls to the RaftNode's handlers.
     */
    private class RaftServiceImpl extends RaftServiceGrpc.RaftServiceImplBase {

        @Override
        public void requestVote(VoteRequest request, StreamObserver<VoteResponse> responseObserver) {
            try (Scope remoteContext = telemetryContext().makeCurrent();
                 MDC.MDCCloseable nodeContext = MDC.putCloseable("nodeId", raftNode.getNodeId());
                 MDC.MDCCloseable rpcContext = MDC.putCloseable("rpcType", "RequestVote");
                 MDC.MDCCloseable requestContext = MDC.putCloseable("requestId", requestId())) {
            Span span = tracer.spanBuilder("raft.RequestVote")
                    .setSpanKind(SpanKind.SERVER)
                    .setAttribute("rpc.system", "grpc")
                    .setAttribute("rpc.method", "RequestVote")
                    .setAttribute("raft.candidate", request.getCandidateId())
                    .setAttribute("raft.term", request.getTerm())
                    .startSpan();
            try (Scope ignored = span.makeCurrent()) {
            logger.debug("Received RequestVote from {} for term {}", request.getCandidateId(), request.getTerm());
            raftNode.handleVoteRequest(request)
                    .whenComplete(Completions.outcome(
                            response -> {
                                span.setAttribute("raft.vote_granted", response.getVoteGranted());
                                span.end();
                                logger.debug("Responding to RequestVote: granted={}, term={}",
                                        response.getVoteGranted(), response.getTerm());
                                responseObserver.onNext(response);
                                responseObserver.onCompleted();
                            },
                            e -> {
                                failRpc("RequestVote", span, e, responseObserver);
                            }));
            } catch (Throwable error) {
                failRpc("RequestVote", span, error, responseObserver);
            }
            }
        }

        @Override
        public void preVote(PreVoteRequest request, StreamObserver<PreVoteResponse> responseObserver) {
            try (Scope remoteContext = telemetryContext().makeCurrent();
                 MDC.MDCCloseable nodeContext = MDC.putCloseable("nodeId", raftNode.getNodeId());
                 MDC.MDCCloseable rpcContext = MDC.putCloseable("rpcType", "PreVote");
                 MDC.MDCCloseable requestContext = MDC.putCloseable("requestId", requestId())) {
            Span span = tracer.spanBuilder("raft.PreVote")
                    .setSpanKind(SpanKind.SERVER)
                    .setAttribute("rpc.system", "grpc")
                    .setAttribute("rpc.method", "PreVote")
                    .setAttribute("raft.candidate", request.getCandidateId())
                    .setAttribute("raft.term", request.getTerm())
                    .startSpan();
            try (Scope ignored = span.makeCurrent()) {
            logger.debug("Received PreVote from {} for term {}", request.getCandidateId(), request.getTerm());
            raftNode.handlePreVoteRequest(request)
                    .whenComplete(Completions.outcome(
                            response -> {
                                span.setAttribute("raft.vote_granted", response.getVoteGranted());
                                span.end();
                                logger.debug("Responding to PreVote: granted={}, term={}",
                                        response.getVoteGranted(), response.getTerm());
                                responseObserver.onNext(response);
                                responseObserver.onCompleted();
                            },
                            e -> {
                                failRpc("PreVote", span, e, responseObserver);
                            }));
            } catch (Throwable error) {
                failRpc("PreVote", span, error, responseObserver);
            }
            }
        }

        @Override
        public void appendEntries(AppendEntriesRequest request, StreamObserver<AppendEntriesResponse> responseObserver) {
            try (Scope remoteContext = telemetryContext().makeCurrent();
                 MDC.MDCCloseable nodeContext = MDC.putCloseable("nodeId", raftNode.getNodeId());
                 MDC.MDCCloseable rpcContext = MDC.putCloseable("rpcType", "AppendEntries");
                 MDC.MDCCloseable requestContext = MDC.putCloseable("requestId", requestId())) {
            Span span = tracer.spanBuilder("raft.AppendEntries")
                    .setSpanKind(SpanKind.SERVER)
                    .setAttribute("rpc.system", "grpc")
                    .setAttribute("rpc.method", "AppendEntries")
                    .setAttribute("raft.leader", request.getLeaderId())
                    .setAttribute("raft.term", request.getTerm())
                    .setAttribute("raft.entries_count", request.getEntriesCount())
                    .startSpan();
            try (Scope ignored = span.makeCurrent()) {
            logAppendEntriesRequest(request);
            raftNode.handleAppendEntriesRequest(request)
                    .whenComplete(Completions.outcome(
                            response -> {
                                span.setAttribute("raft.success", response.getSuccess());
                                span.end();
                                logAppendEntriesResponse(request, response);
                                responseObserver.onNext(response);
                                responseObserver.onCompleted();
                            },
                            e -> {
                                failRpc("AppendEntries", span, e, responseObserver);
                            }));
            } catch (Throwable error) {
                failRpc("AppendEntries", span, error, responseObserver);
            }
            }
        }

        @Override
        public void join(JoinRequest request, StreamObserver<MembershipResponse> responseObserver) {
            answer(membership == null ? null : membership.join(request),
                    responseObserver);
        }

        @Override
        public void removeServer(RemoveServerRequest request, StreamObserver<MembershipResponse> responseObserver) {
            answer(membership == null ? null : membership.remove(request),
                    responseObserver);
        }

        private void answer(CompletableFuture<MembershipResponse> response, StreamObserver<MembershipResponse> observer) {
            if (response == null) {
                response = CompletableFuture.completedFuture(MembershipResponse.newBuilder()
                        .setStatus(MembershipResponse.Status.NO_LEADER)
                        .setMessage(raftNode.getNodeId() + " does not serve membership changes").build());
            }
            response.whenComplete(Completions.outcome(
                    answer -> {
                        observer.onNext(answer);
                        observer.onCompleted();
                    },
                    error -> observer.onError(Status.UNAVAILABLE
                            .withDescription("Membership change failed").withCause(error).asRuntimeException())));
        }

        @Override
        public void describe(DescribeRequest request, StreamObserver<DescribeResponse> responseObserver) {
            raftNode.describe()
                    .whenComplete(Completions.outcome(description -> {
                        responseObserver.onNext(description);
                        responseObserver.onCompleted();
                    },
                            error -> responseObserver.onError(Status.UNAVAILABLE
                            .withDescription("Describe failed").withCause(error).asRuntimeException())));
        }

        @Override
        public void installSnapshot(InstallSnapshotRequest request, StreamObserver<InstallSnapshotResponse> responseObserver) {
            try (Scope remoteContext = telemetryContext().makeCurrent();
                 MDC.MDCCloseable nodeContext = MDC.putCloseable("nodeId", raftNode.getNodeId());
                 MDC.MDCCloseable rpcContext = MDC.putCloseable("rpcType", "InstallSnapshot");
                 MDC.MDCCloseable requestContext = MDC.putCloseable("requestId", requestId())) {
            Span span = tracer.spanBuilder("raft.InstallSnapshot")
                    .setSpanKind(SpanKind.SERVER)
                    .setAttribute("rpc.system", "grpc")
                    .setAttribute("rpc.method", "InstallSnapshot")
                    .setAttribute("raft.leader", request.getLeaderId())
                    .setAttribute("raft.term", request.getTerm())
                    .startSpan();
            try (Scope ignored = span.makeCurrent()) {
            logger.debug("Received InstallSnapshot from {} for term {}, lastIncludedIndex={}, chunk {}/{}",
                    request.getLeaderId(), request.getTerm(), request.getLastIncludedIndex(),
                    request.getChunkIndex() + 1, request.getTotalChunks());
            raftNode.handleInstallSnapshot(request)
                    .whenComplete(Completions.outcome(response -> {
                        span.setAttribute("raft.success", response.getSuccess());
                        span.end();
                        logger.debug("Responding to InstallSnapshot: success={}, term={}",
                                response.getSuccess(), response.getTerm());
                        responseObserver.onNext(response);
                        responseObserver.onCompleted();
                    },
                    e -> {
                        failRpc("InstallSnapshot", span, e, responseObserver);
                    }));
            } catch (Throwable error) {
                failRpc("InstallSnapshot", span, error, responseObserver);
            }
            }
        }
    }

    private static String requestId() {
        String requestId = REQUEST_ID_CONTEXT.get();
        return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
    }

    private static io.opentelemetry.context.Context telemetryContext() {
        io.opentelemetry.context.Context context = TELEMETRY_CONTEXT.get();
        return context == null ? io.opentelemetry.context.Context.current() : context;
    }

    private static void logAppendEntriesRequest(AppendEntriesRequest request) {
        if (request.getEntriesCount() == 0) {
            logger.trace("Received AppendEntries heartbeat from {} for term {}", request.getLeaderId(), request.getTerm());
        } else {
            logger.debug("Received AppendEntries from {} for term {}, entries={}",
                    request.getLeaderId(), request.getTerm(), request.getEntriesCount());
        }
    }

    private static void logAppendEntriesResponse(AppendEntriesRequest request, AppendEntriesResponse response) {
        if (request.getEntriesCount() == 0 && response.getSuccess()) {
            logger.trace("Responding to AppendEntries heartbeat: success=true, term={}", response.getTerm());
        } else {
            logger.debug("Responding to AppendEntries: success={}, term={}", response.getSuccess(), response.getTerm());
        }
    }

    private static void failRpc(String rpcType, Span span, Throwable error, StreamObserver<?> observer) {
        span.setStatus(StatusCode.ERROR, error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        span.recordException(error);
        span.end();
        Status status;
        if (error instanceof IllegalArgumentException) {
            status = Status.INVALID_ARGUMENT;
            logger.warn("Rejected invalid {} request: {}", rpcType, error.getMessage());
        } else {
            status = Status.INTERNAL;
            logger.error("Failure handling {}", rpcType, error);
        }
        observer.onError(status.withDescription(rpcType + " failed").withCause(error).asRuntimeException());
    }

    private static final class CorrelationIdServerInterceptor implements ServerInterceptor {
        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            String requestId = headers.get(REQUEST_ID_HEADER);
            if (requestId == null || requestId.isBlank()) requestId = UUID.randomUUID().toString();
            io.opentelemetry.context.Context remoteTelemetryContext =
                    GlobalOpenTelemetry.getPropagators().getTextMapPropagator()
                            .extract(io.opentelemetry.context.Context.current(), headers, METADATA_GETTER);
            Context grpcContext = Context.current()
                    .withValue(REQUEST_ID_CONTEXT, requestId)
                    .withValue(TELEMETRY_CONTEXT, remoteTelemetryContext);
            return Contexts.interceptCall(grpcContext, call, headers, next);
        }
    }
}
