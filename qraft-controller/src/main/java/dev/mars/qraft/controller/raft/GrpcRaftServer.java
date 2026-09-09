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

package dev.mars.qraft.controller.raft;

import dev.mars.qraft.controller.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.controller.raft.grpc.AppendEntriesResponse;
import dev.mars.qraft.controller.raft.grpc.InstallSnapshotRequest;
import dev.mars.qraft.controller.raft.grpc.InstallSnapshotResponse;
import dev.mars.qraft.controller.raft.grpc.RaftServiceGrpc;
import dev.mars.qraft.controller.raft.grpc.VoteRequest;
import dev.mars.qraft.controller.raft.grpc.VoteResponse;
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
import dev.mars.qraft.controller.runtime.Future;
import dev.mars.qraft.controller.runtime.JavaRuntime;
import dev.mars.qraft.controller.runtime.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.UUID;
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
    private static final Tracer tracer = GlobalOpenTelemetry.getTracer("qraft-controller");
    private static final Metadata.Key<String> REQUEST_ID_HEADER =
            Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER);
    private static final Context.Key<String> REQUEST_ID_CONTEXT = Context.key("qraft-request-id");
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

    private final JavaRuntime runtime;
    private final int port;
    private final RaftNode raftNode;
    private final BindableService[] extraServices;
    private Server server;

    public GrpcRaftServer(JavaRuntime runtime, int port, RaftNode raftNode) {
        this(runtime, port, raftNode, new BindableService[0]);
    }

    public GrpcRaftServer(JavaRuntime runtime, int port, RaftNode raftNode, BindableService... extraServices) {
        this.runtime = runtime;
        this.port = port;
        this.raftNode = raftNode;
        this.extraServices = extraServices != null ? extraServices : new BindableService[0];
    }

    /**
     * Start the gRPC server.
     * 
     * @return Future that completes when server is started
     */
    public Future<Void> start() {
        Promise<Void> promise = Promise.promise();

        runtime.executeBlocking(() -> {
            try {
            ServerBuilder<?> builder = ServerBuilder.forPort(port)
                .intercept(new CorrelationIdServerInterceptor())
                .addService(new RaftServiceImpl());

            for (BindableService service : extraServices) {
                builder.addService(service);
            }

            server = builder.build().start();
                logger.info("gRPC Raft server started on port {}", port);
                return null;
            } catch (IOException e) {
                throw new RuntimeException("Failed to start gRPC server on port " + port, e);
            }
        }).onSuccess(v -> promise.complete())
          .onFailure(promise::fail);

        return promise.future();
    }

    /**
     * Stop the gRPC server gracefully.
     * 
     * @return Future that completes when server is stopped
     */
    public Future<Void> stop() {
        Promise<Void> promise = Promise.promise();

        if (server == null) {
            promise.complete();
            return promise.future();
        }

        runtime.executeBlocking(() -> {
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
        }).onSuccess(v -> promise.complete())
          .onFailure(promise::fail);

        return promise.future();
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
                    .onSuccess(response -> {
                        span.setAttribute("raft.vote_granted", response.getVoteGranted());
                        span.end();
                        logger.debug("Responding to RequestVote: granted={}, term={}", 
                                response.getVoteGranted(), response.getTerm());
                        responseObserver.onNext(response);
                        responseObserver.onCompleted();
                    })
                    .onFailure(e -> {
                        failRpc("RequestVote", span, e, responseObserver);
                    });
            } catch (Throwable error) {
                failRpc("RequestVote", span, error, responseObserver);
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
                    .onSuccess(response -> {
                        span.setAttribute("raft.success", response.getSuccess());
                        span.end();
                        logAppendEntriesResponse(request, response);
                        responseObserver.onNext(response);
                        responseObserver.onCompleted();
                    })
                    .onFailure(e -> {
                        failRpc("AppendEntries", span, e, responseObserver);
                    });
            } catch (Throwable error) {
                failRpc("AppendEntries", span, error, responseObserver);
            }
            }
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
                    .onSuccess(response -> {
                        span.setAttribute("raft.success", response.getSuccess());
                        span.end();
                        logger.debug("Responding to InstallSnapshot: success={}, term={}",
                                response.getSuccess(), response.getTerm());
                        responseObserver.onNext(response);
                        responseObserver.onCompleted();
                    })
                    .onFailure(e -> {
                        failRpc("InstallSnapshot", span, e, responseObserver);
                    });
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
