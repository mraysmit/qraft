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

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import dev.mars.qraft.raft.grpc.AppendEntriesRequest;
import dev.mars.qraft.raft.grpc.AppendEntriesResponse;
import dev.mars.qraft.raft.grpc.DescribeRequest;
import dev.mars.qraft.raft.grpc.DescribeResponse;
import dev.mars.qraft.raft.grpc.InstallSnapshotRequest;
import dev.mars.qraft.raft.grpc.InstallSnapshotResponse;
import dev.mars.qraft.raft.grpc.JoinRequest;
import dev.mars.qraft.raft.grpc.MembershipResponse;
import dev.mars.qraft.raft.grpc.RemoveServerRequest;
import dev.mars.qraft.raft.grpc.RaftServiceGrpc;
import dev.mars.qraft.raft.grpc.VoteRequest;
import dev.mars.qraft.raft.grpc.VoteResponse;
import dev.mars.qraft.raft.metrics.RaftMetrics;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;
import dev.mars.qraft.common.async.Future;
import dev.mars.qraft.common.async.JavaRuntime;
import dev.mars.qraft.common.async.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.net.SocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * gRPC implementation of RaftTransport using standard gRPC Netty client.
 * <p>
 * Uses a bounded ThreadPoolExecutor for gRPC callbacks instead of
 * an unbounded CachedThreadPool to prevent resource exhaustion.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @version 2.0 (T3.2: Bounded Thread Pools)
 * @since 2025-12-16
 */
public class GrpcRaftTransport implements RaftTransport {

    private static final Logger logger = LoggerFactory.getLogger(GrpcRaftTransport.class);
    private static final String THREAD_NAME_PREFIX = "raft-grpc-io-";
    /** A server that cannot describe itself this quickly is treated as unreachable for this bootstrap attempt. */
    private static final long DESCRIBE_DEADLINE_SECONDS = 5;
    private static final Metadata.Key<String> REQUEST_ID_HEADER =
            Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER);
    private static final TextMapSetter<Metadata> METADATA_SETTER = (carrier, key, value) ->
            carrier.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), value);
    private static final Tracer tracer = GlobalOpenTelemetry.getTracer("qraft-raft");

    private final JavaRuntime runtime;
    private final String selfId;
    private final Map<String, String> clusterNodes; // nodeId -> host:port
    /** Addresses from the configuration in force, which override the listed ones; see {@link #useAddresses}. */
    private final Map<String, String> configuredAddresses = new ConcurrentHashMap<>();
    private final Map<String, RaftServiceGrpc.RaftServiceFutureStub> clients = new ConcurrentHashMap<>();
    private final Map<String, ManagedChannel> channels = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final int poolSize;
    private final int queueSize;
    /** Set by {@link #stop()}; a send after it fails at once, since its reply could no longer be delivered. */
    private volatile boolean stopped;

    private RaftNode raftNode; // Circular dependency injection

    /**
     * Creates a GrpcRaftTransport with default pool configuration.
     * 
     * @param runtime Java runtime instance
     * @param selfId this node's ID
     * @param clusterNodes map of nodeId to host:port
     */
    public GrpcRaftTransport(JavaRuntime runtime, String selfId, Map<String, String> clusterNodes) {
        this(runtime, selfId, clusterNodes, 10, 1000);
    }

    /**
     * Creates a GrpcRaftTransport with custom pool configuration.
     * 
     * @param runtime Java runtime instance
     * @param selfId this node's ID
     * @param clusterNodes map of nodeId to host:port
     * @param poolSize maximum number of worker threads for gRPC callbacks
     * @param queueSize maximum number of queued tasks before back-pressure
     */
    public GrpcRaftTransport(JavaRuntime runtime, String selfId, Map<String, String> clusterNodes,
                              int poolSize, int queueSize) {
        this.runtime = runtime;
        this.selfId = selfId;
        this.clusterNodes = clusterNodes;
        this.poolSize = poolSize;
        this.queueSize = queueSize;
        
        ThreadPoolExecutor threadPool = callbackExecutor(selfId, poolSize, queueSize);
        this.executor = threadPool;
        
        // Register with metrics for monitoring
        RaftMetrics.getInstance().registerThreadPool(selfId, threadPool);
        
        logger.debug("GrpcRaftTransport created with bounded ThreadPoolExecutor (poolSize={}, queueSize={})",
                poolSize, queueSize);
    }
    
    /**
     * The bounded pool that runs gRPC reply callbacks: {@code poolSize} named daemon threads and a queue of
     * {@code queueSize}. When both are full, the thread delivering the reply runs its callback itself, so a reply
     * is slowed but never dropped.
     */
    static ThreadPoolExecutor callbackExecutor(String selfId, int poolSize, int queueSize) {
        AtomicInteger threadCounter = new AtomicInteger(0);
        return new ThreadPoolExecutor(
                poolSize,           // core pool size
                poolSize,           // max pool size (fixed)
                60L, TimeUnit.SECONDS,  // keep-alive for idle threads
                new LinkedBlockingQueue<>(queueSize),  // bounded work queue
                r -> {
                    Thread t = new Thread(r, THREAD_NAME_PREFIX + selfId + "-" + threadCounter.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()  // back-pressure when queue is full
        );
    }

    /**
     * Gets the current pool size configuration.
     * 
     * @return the pool size
     */
    public int getPoolSize() {
        return poolSize;
    }

    /**
     * Gets the current queue size configuration.
     * 
     * @return the queue size
     */
    public int getQueueSize() {
        return queueSize;
    }

    public void setRaftNode(RaftNode raftNode) {
        this.raftNode = raftNode;
    }

    @Override
    public void start(Consumer<RaftMessage> messageHandler) {
        // Server side should be started in the Verticle separately (GrpcRaftServer)
        // Client side just needs to be ready
        logger.info("GrpcRaftTransport initialized for node: {} (poolSize={}, queueSize={})", selfId, poolSize, queueSize);
    }

    @Override
    public void stop() {
        stopped = true;
        RaftMetrics.getInstance().unregisterThreadPool(selfId);
        clients.clear();
        for (ManagedChannel channel : channels.values()) {
            channel.shutdown();
        }
        for (Map.Entry<String, ManagedChannel> entry : channels.entrySet()) {
            ManagedChannel channel = entry.getValue();
            try {
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                    channel.shutdownNow();
                    logger.warn("Forced gRPC channel shutdown for peer: {}", entry.getKey());
                }
            } catch (InterruptedException e) {
                channel.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        channels.clear();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                logger.warn("GrpcRaftTransport executor forced shutdown for node: {}", selfId);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.debug("GrpcRaftTransport executor closed for node: {}", selfId);
    }

    @Override
    public Future<VoteResponse> sendVoteRequest(String targetId, VoteRequest request) {
        requireKnownTarget(targetId);
        if (stopped) {
            // The callback executor is shut down, so the reply to a call made now would be discarded.
            return Future.failedFuture(new IllegalStateException("Transport for " + selfId + " is stopped"));
        }
        String requestId = requestId();
        Span span = tracer.spanBuilder("raft.RequestVote")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("rpc.system", "grpc")
                .setAttribute("rpc.method", "RequestVote")
                .setAttribute("raft.target", targetId)
                .setAttribute("raft.term", request.getTerm())
                .setAttribute("raft.candidate", request.getCandidateId())
                .startSpan();
        try (MDC.MDCCloseable ignoredMdc = MDC.putCloseable("requestId", requestId);
             Scope ignoredSpan = span.makeCurrent()) {
        return toFuture(correlatedStub(targetId, requestId).requestVote(request))
                .onSuccess(r -> {
                    span.setAttribute("raft.vote_granted", r.getVoteGranted());
                    span.end();
                })
                .onFailure(e -> {
                    span.setStatus(StatusCode.ERROR, e.getMessage());
                    span.recordException(e);
                    span.end();
                });
        } catch (Throwable error) {
            finishSpanWithError(span, error);
            return Future.failedFuture(error);
        }
    }

    @Override
    public Future<AppendEntriesResponse> sendAppendEntries(String targetId, AppendEntriesRequest request) {
        requireKnownTarget(targetId);
        if (stopped) {
            // The callback executor is shut down, so the reply to a call made now would be discarded.
            return Future.failedFuture(new IllegalStateException("Transport for " + selfId + " is stopped"));
        }
        String requestId = requestId();
        Span span = tracer.spanBuilder("raft.AppendEntries")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("rpc.system", "grpc")
                .setAttribute("rpc.method", "AppendEntries")
                .setAttribute("raft.target", targetId)
                .setAttribute("raft.term", request.getTerm())
                .setAttribute("raft.entries_count", request.getEntriesCount())
                .startSpan();
        try (MDC.MDCCloseable ignoredMdc = MDC.putCloseable("requestId", requestId);
             Scope ignoredSpan = span.makeCurrent()) {
        return toFuture(correlatedStub(targetId, requestId).appendEntries(request))
                .onSuccess(r -> {
                    span.setAttribute("raft.success", r.getSuccess());
                    span.end();
                })
                .onFailure(e -> {
                    span.setStatus(StatusCode.ERROR, e.getMessage());
                    span.recordException(e);
                    span.end();
                });
        } catch (Throwable error) {
            finishSpanWithError(span, error);
            return Future.failedFuture(error);
        }
    }

    /** Like every send here, but an unaddressable target fails the future: a forwarder answers from it. */
    @Override
    public Future<MembershipResponse> join(String targetId, JoinRequest request) {
        if (stopped) {
            return Future.failedFuture(new IllegalStateException("Transport for " + selfId + " is stopped"));
        }
        try {
            requireKnownTarget(targetId);
            return toFuture(getStub(targetId)
                    .withDeadlineAfter(MembershipService.TIMEOUT_SECONDS, TimeUnit.SECONDS).join(request));
        } catch (Throwable error) {
            return Future.failedFuture(error);
        }
    }

    @Override
    public Future<MembershipResponse> removeServer(String targetId, RemoveServerRequest request) {
        if (stopped) {
            return Future.failedFuture(new IllegalStateException("Transport for " + selfId + " is stopped"));
        }
        try {
            requireKnownTarget(targetId);
            return toFuture(getStub(targetId)
                    .withDeadlineAfter(MembershipService.TIMEOUT_SECONDS, TimeUnit.SECONDS).removeServer(request));
        } catch (Throwable error) {
            return Future.failedFuture(error);
        }
    }

    /**
     * Takes the configured servers' addresses. A server whose address changed is dialled afresh; a listed
     * server stays reachable at its listed address, for joining and bootstrapping.
     */
    @Override
    public void useAddresses(Map<String, String> addresses) {
        for (Map.Entry<String, String> entry : addresses.entrySet()) {
            if (entry.getKey().equals(selfId)) continue;
            String previous = configuredAddresses.put(entry.getKey(), entry.getValue());
            String dialled = previous != null ? previous : clusterNodes.get(entry.getKey());
            if (dialled != null && !dialled.equals(entry.getValue())) {
                clients.remove(entry.getKey());
                ManagedChannel channel = channels.remove(entry.getKey());
                if (channel != null) channel.shutdown();
            }
        }
    }

    private String addressOf(String targetId) {
        String configured = configuredAddresses.get(targetId);
        return configured != null ? configured : clusterNodes.get(targetId);
    }

    @Override
    public Future<DescribeResponse> describe(String targetId) {
        requireKnownTarget(targetId);
        if (stopped) {
            return Future.failedFuture(new IllegalStateException("Transport for " + selfId + " is stopped"));
        }
        try {
            return toFuture(getStub(targetId).withDeadlineAfter(DESCRIBE_DEADLINE_SECONDS, TimeUnit.SECONDS)
                    .describe(DescribeRequest.getDefaultInstance()));
        } catch (Throwable error) {
            return Future.failedFuture(error);
        }
    }

    @Override
    public Future<InstallSnapshotResponse> sendInstallSnapshot(String targetId, InstallSnapshotRequest request) {
        requireKnownTarget(targetId);
        if (stopped) {
            // The callback executor is shut down, so the reply to a call made now would be discarded.
            return Future.failedFuture(new IllegalStateException("Transport for " + selfId + " is stopped"));
        }
        String requestId = requestId();
        Span span = tracer.spanBuilder("raft.InstallSnapshot")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("rpc.system", "grpc")
                .setAttribute("rpc.method", "InstallSnapshot")
                .setAttribute("raft.target", targetId)
                .setAttribute("raft.term", request.getTerm())
                .startSpan();
        try (MDC.MDCCloseable ignoredMdc = MDC.putCloseable("requestId", requestId);
             Scope ignoredSpan = span.makeCurrent()) {
        return toFuture(correlatedStub(targetId, requestId).installSnapshot(request))
                .onSuccess(r -> {
                    span.setAttribute("raft.success", r.getSuccess());
                    span.end();
                })
                .onFailure(e -> {
                    span.setStatus(StatusCode.ERROR, e.getMessage());
                    span.recordException(e);
                    span.end();
                });
        } catch (Throwable error) {
            finishSpanWithError(span, error);
            return Future.failedFuture(error);
        }
    }

    private RaftServiceGrpc.RaftServiceFutureStub getStub(String targetId) {
        return clients.computeIfAbsent(targetId, id -> {
            String addr = addressOf(id);
            if (addr == null) {
                throw new IllegalArgumentException("Unknown node: " + id);
            }
            String[] parts = addr.split(":");
            String host = parts[0];
            int port = Integer.parseInt(parts[1]);

            ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
                    .usePlaintext()
                    .build();
            channels.put(id, channel);
            return RaftServiceGrpc.newFutureStub(channel);
        });
    }

    private RaftServiceGrpc.RaftServiceFutureStub correlatedStub(String targetId, String requestId) {
        Metadata headers = new Metadata();
        headers.put(REQUEST_ID_HEADER, requestId);
        GlobalOpenTelemetry.getPropagators().getTextMapPropagator()
                .inject(Context.current(), headers, METADATA_SETTER);
        return getStub(targetId).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    private void requireKnownTarget(String targetId) {
        if (addressOf(targetId) == null) {
            throw new IllegalArgumentException("Unknown node: " + targetId);
        }
    }

    private static String requestId() {
        String current = MDC.get("requestId");
        return current == null || current.isBlank() ? UUID.randomUUID().toString() : current;
    }

    private static void finishSpanWithError(Span span, Throwable error) {
        span.setStatus(StatusCode.ERROR, error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        span.recordException(error);
        span.end();
    }

    private <T> Future<T> toFuture(ListenableFuture<T> listenableFuture) {
        Promise<T> promise = Promise.promise();
        Map<String, String> currentContext = MDC.getCopyOfContextMap();
        Map<String, String> capturedContext = currentContext == null ? Map.of() : new HashMap<>(currentContext);
        Context telemetryContext = Context.current();
        Futures.addCallback(listenableFuture, new FutureCallback<T>() {
            @Override
            public void onSuccess(T result) {
                runtime.runOnContext(v -> promise.complete(result));
            }

            @Override
            public void onFailure(Throwable t) {
                runtime.runOnContext(v -> promise.fail(t));
            }
        }, command -> executor.execute(() -> withContext(capturedContext, telemetryContext, command)));
        return promise.future();
    }

    private static void withContext(Map<String, String> context, Context telemetryContext, Runnable action) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try (Scope ignored = telemetryContext.makeCurrent()) {
            if (context.isEmpty()) MDC.clear();
            else MDC.setContextMap(context);
            action.run();
        } finally {
            if (previous == null || previous.isEmpty()) MDC.clear();
            else MDC.setContextMap(previous);
        }
    }
}
