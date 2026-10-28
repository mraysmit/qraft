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
import dev.mars.qraft.raft.grpc.PreVoteRequest;
import dev.mars.qraft.raft.grpc.PreVoteResponse;
import dev.mars.qraft.raft.grpc.RemoveServerRequest;
import dev.mars.qraft.raft.grpc.RaftServiceGrpc;
import dev.mars.qraft.raft.grpc.VoteRequest;
import dev.mars.qraft.raft.grpc.VoteResponse;
import dev.mars.qraft.raft.metrics.RaftMetrics;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;
import dev.mars.qraft.common.async.RaftStateLoop;
import dev.mars.qraft.common.concurrent.Completions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
 * <p>
 * Every call has a deadline, so one that is never answered fails with {@code DEADLINE_EXCEEDED} instead of
 * waiting forever: a vote, pre-vote or append after {@link #ROUND_TRIP_DEADLINE}, a snapshot chunk after
 * {@link #snapshotChunkDeadline}, and the membership and describe calls after their own. A connection that
 * stops answering is closed by keepalive, so the next call dials the peer again.
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
    /**
     * How long a vote, pre-vote or append waits for its answer: Consul's Raft transport timeout. It ends a call
     * whose connection died without a reset, which would otherwise wait forever and log nothing, and it is far
     * longer than any answer on a working network takes, so it never cuts one short.
     */
    static final Duration ROUND_TRIP_DEADLINE = Duration.ofSeconds(10);
    /** A snapshot chunk waits one round-trip deadline for every this many bytes it carries; see below. */
    static final int SNAPSHOT_DEADLINE_SCALE_BYTES = 256 * 1024;
    /**
     * How long a connection may go without reading anything before it is pinged: gRPC's minimum. With
     * {@link #KEEPALIVE_TIMEOUT} it closes a connection that died without a reset, so the next call dials again;
     * a deadline alone ends only the call, and gRPC would go on sending every later call into the dead connection.
     */
    static final Duration KEEPALIVE_TIME = Duration.ofSeconds(10);
    /** How long a ping waits for its acknowledgement before the connection is closed. */
    static final Duration KEEPALIVE_TIMEOUT = Duration.ofSeconds(10);
    private static final Metadata.Key<String> REQUEST_ID_HEADER =
            Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER);
    private static final TextMapSetter<Metadata> METADATA_SETTER = (carrier, key, value) ->
            carrier.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), value);
    private static final Tracer tracer = GlobalOpenTelemetry.getTracer("qraft-raft");

    private final RaftStateLoop runtime;
    private final String selfId;
    private final Map<String, String> clusterNodes; // nodeId -> host:port
    /** Addresses from the configuration in force, which override the listed ones; see {@link #useAddresses}. */
    private final Map<String, String> configuredAddresses = new ConcurrentHashMap<>();
    private final Map<String, RaftServiceGrpc.RaftServiceFutureStub> clients = new ConcurrentHashMap<>();
    private final Map<String, ManagedChannel> channels = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final int poolSize;
    private final int queueSize;
    private final Duration roundTripDeadline;
    private final Duration keepAliveTimeout;
    /** Set by {@link #stop()}; a send after it fails at once, since its reply could no longer be delivered. */
    private volatile boolean stopped;

    private RaftNode raftNode; // Circular dependency injection

    /**
     * Creates a GrpcRaftTransport with default pool configuration.
     * 
     * @param runtime Raft state loop
     * @param selfId this node's ID
     * @param clusterNodes map of nodeId to host:port
     */
    public GrpcRaftTransport(RaftStateLoop runtime, String selfId, Map<String, String> clusterNodes) {
        this(runtime, selfId, clusterNodes, 10, 1000);
    }

    /**
     * Creates a GrpcRaftTransport with custom pool configuration.
     * 
     * @param runtime Raft state loop
     * @param selfId this node's ID
     * @param clusterNodes map of nodeId to host:port
     * @param poolSize maximum number of worker threads for gRPC callbacks
     * @param queueSize maximum number of queued tasks before back-pressure
     */
    public GrpcRaftTransport(RaftStateLoop runtime, String selfId, Map<String, String> clusterNodes,
                              int poolSize, int queueSize) {
        this(runtime, selfId, clusterNodes, poolSize, queueSize, ROUND_TRIP_DEADLINE, KEEPALIVE_TIMEOUT);
    }

    /**
     * Creates a GrpcRaftTransport with the given deadline and keepalive timeout.
     *
     * @param roundTripDeadline how long a vote, pre-vote or append may wait for its answer; a snapshot chunk's
     *     deadline is scaled from it by {@link #snapshotChunkDeadline}
     * @param keepAliveTimeout how long a keepalive ping waits before its connection is closed
     */
    GrpcRaftTransport(RaftStateLoop runtime, String selfId, Map<String, String> clusterNodes,
                      int poolSize, int queueSize, Duration roundTripDeadline, Duration keepAliveTimeout) {
        this.runtime = runtime;
        this.selfId = selfId;
        this.clusterNodes = clusterNodes;
        this.poolSize = poolSize;
        this.queueSize = queueSize;
        this.roundTripDeadline = Objects.requireNonNull(roundTripDeadline, "roundTripDeadline");
        this.keepAliveTimeout = Objects.requireNonNull(keepAliveTimeout, "keepAliveTimeout");

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
    public CompletableFuture<VoteResponse> sendVoteRequest(String targetId, VoteRequest request) {
        requireKnownTarget(targetId);
        if (stopped) {
            // The callback executor is shut down, so the reply to a call made now would be discarded.
            return CompletableFuture.failedFuture(new IllegalStateException("Transport for " + selfId + " is stopped"));
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
            CompletableFuture<VoteResponse> response = toFuture(correlatedStub(targetId, requestId, roundTripDeadline).requestVote(request));
            response.whenComplete(Completions.outcome(
                    r -> {
                        span.setAttribute("raft.vote_granted", r.getVoteGranted());
                        span.end();
                    },
                    e -> {
                        span.setStatus(StatusCode.ERROR, e.getMessage());
                        span.recordException(e);
                        span.end();
                    }));
            return response;
        } catch (Throwable error) {
            finishSpanWithError(span, error);
            return CompletableFuture.failedFuture(error);
        }
    }

    @Override
    public CompletableFuture<PreVoteResponse> sendPreVote(String targetId, PreVoteRequest request) {
        requireKnownTarget(targetId);
        if (stopped) {
            // The callback executor is shut down, so the reply to a call made now would be discarded.
            return CompletableFuture.failedFuture(new IllegalStateException("Transport for " + selfId + " is stopped"));
        }
        String requestId = requestId();
        Span span = tracer.spanBuilder("raft.PreVote")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute("rpc.system", "grpc")
                .setAttribute("rpc.method", "PreVote")
                .setAttribute("raft.target", targetId)
                .setAttribute("raft.term", request.getTerm())
                .setAttribute("raft.candidate", request.getCandidateId())
                .startSpan();
        try (MDC.MDCCloseable ignoredMdc = MDC.putCloseable("requestId", requestId);
             Scope ignoredSpan = span.makeCurrent()) {
            // A server of an older version has no PreVote. Its UNIMPLEMENTED answer becomes the unsupported failure
            // that a transport without the capability gives, so the caller counts that peer as willing.
            CompletableFuture<PreVoteResponse> response = toFuture(correlatedStub(targetId, requestId, roundTripDeadline).preVote(request))
                    .exceptionallyCompose(failure -> CompletableFuture.failedFuture(
                            Completions.unwrap(failure) instanceof StatusRuntimeException status
                                    && status.getStatus().getCode() == Status.Code.UNIMPLEMENTED
                                    ? new UnsupportedOperationException(targetId + " does not implement PreVote", status)
                                    : failure));
            response.whenComplete(Completions.outcome(
                    r -> {
                        span.setAttribute("raft.vote_granted", r.getVoteGranted());
                        span.end();
                    },
                    e -> {
                        span.setStatus(StatusCode.ERROR, e.getMessage());
                        span.recordException(e);
                        span.end();
                    }));
            return response;
        } catch (Throwable error) {
            finishSpanWithError(span, error);
            return CompletableFuture.failedFuture(error);
        }
    }

    @Override
    public CompletableFuture<AppendEntriesResponse> sendAppendEntries(String targetId, AppendEntriesRequest request) {
        requireKnownTarget(targetId);
        if (stopped) {
            // The callback executor is shut down, so the reply to a call made now would be discarded.
            return CompletableFuture.failedFuture(new IllegalStateException("Transport for " + selfId + " is stopped"));
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
            CompletableFuture<AppendEntriesResponse> response = toFuture(correlatedStub(targetId, requestId, roundTripDeadline).appendEntries(request));
            response.whenComplete(Completions.outcome(
                    r -> {
                        span.setAttribute("raft.success", r.getSuccess());
                        span.end();
                    },
                    e -> {
                        span.setStatus(StatusCode.ERROR, e.getMessage());
                        span.recordException(e);
                        span.end();
                    }));
            return response;
        } catch (Throwable error) {
            finishSpanWithError(span, error);
            return CompletableFuture.failedFuture(error);
        }
    }

    /** Like every send here, but an unaddressable target fails the future: a forwarder answers from it. */
    @Override
    public CompletableFuture<MembershipResponse> join(String targetId, JoinRequest request) {
        if (stopped) {
            return CompletableFuture.failedFuture(new IllegalStateException("Transport for " + selfId + " is stopped"));
        }
        try {
            requireKnownTarget(targetId);
            return toFuture(getStub(targetId)
                    .withDeadlineAfter(MembershipService.TIMEOUT_SECONDS, TimeUnit.SECONDS).join(request));
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    @Override
    public CompletableFuture<MembershipResponse> removeServer(String targetId, RemoveServerRequest request) {
        if (stopped) {
            return CompletableFuture.failedFuture(new IllegalStateException("Transport for " + selfId + " is stopped"));
        }
        try {
            requireKnownTarget(targetId);
            return toFuture(getStub(targetId)
                    .withDeadlineAfter(MembershipService.TIMEOUT_SECONDS, TimeUnit.SECONDS).removeServer(request));
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
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

    /**
     * A channel that could not reach a peer waits out gRPC's reconnect backoff, which grows to two minutes, before it
     * tries again. A peer that has just sent this server a request is reachable, so the wait is cut short and the
     * channel to it reconnects now. A peer with no channel yet has no backoff to cut.
     */
    @Override
    public void peerHeardFrom(String peerName) {
        ManagedChannel channel = channels.get(peerName);
        if (channel != null) channel.resetConnectBackoff();
    }

    private String addressOf(String targetId) {
        String configured = configuredAddresses.get(targetId);
        return configured != null ? configured : clusterNodes.get(targetId);
    }

    @Override
    public CompletableFuture<DescribeResponse> describe(String targetId) {
        requireKnownTarget(targetId);
        if (stopped) {
            return CompletableFuture.failedFuture(new IllegalStateException("Transport for " + selfId + " is stopped"));
        }
        try {
            return toFuture(getStub(targetId).withDeadlineAfter(DESCRIBE_DEADLINE_SECONDS, TimeUnit.SECONDS)
                    .describe(DescribeRequest.getDefaultInstance()));
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    @Override
    public CompletableFuture<InstallSnapshotResponse> sendInstallSnapshot(String targetId, InstallSnapshotRequest request) {
        requireKnownTarget(targetId);
        if (stopped) {
            // The callback executor is shut down, so the reply to a call made now would be discarded.
            return CompletableFuture.failedFuture(new IllegalStateException("Transport for " + selfId + " is stopped"));
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
            CompletableFuture<InstallSnapshotResponse> response = toFuture(correlatedStub(targetId, requestId,
                    snapshotChunkDeadline(roundTripDeadline, request.getData().size())).installSnapshot(request));
            response.whenComplete(Completions.outcome(
                    r -> {
                        span.setAttribute("raft.success", r.getSuccess());
                        span.end();
                    },
                    e -> {
                        span.setStatus(StatusCode.ERROR, e.getMessage());
                        span.recordException(e);
                        span.end();
                    }));
            return response;
        } catch (Throwable error) {
            finishSpanWithError(span, error);
            return CompletableFuture.failedFuture(error);
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

            // Pinged even with no call open, so a follower's idle connection is sound when it next campaigns.
            ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
                    .usePlaintext()
                    .keepAliveTime(KEEPALIVE_TIME.toNanos(), TimeUnit.NANOSECONDS)
                    .keepAliveTimeout(keepAliveTimeout.toNanos(), TimeUnit.NANOSECONDS)
                    .keepAliveWithoutCalls(true)
                    .build();
            channels.put(id, channel);
            return RaftServiceGrpc.newFutureStub(channel);
        });
    }

    /**
     * The stub for one call to {@code targetId}, carrying its request ID and trace context. Its deadline is counted
     * from now, so the call has all of it.
     */
    private RaftServiceGrpc.RaftServiceFutureStub correlatedStub(String targetId, String requestId, Duration deadline) {
        Metadata headers = new Metadata();
        headers.put(REQUEST_ID_HEADER, requestId);
        GlobalOpenTelemetry.getPropagators().getTextMapPropagator()
                .inject(Context.current(), headers, METADATA_SETTER);
        return getStub(targetId).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
                .withDeadlineAfter(deadline.toNanos(), TimeUnit.NANOSECONDS);
    }

    /**
     * The deadline of a snapshot chunk carrying {@code bytes}, by Consul's rule (hashicorp/raft's
     * {@code TimeoutScale}): one round-trip deadline for every {@link #SNAPSHOT_DEADLINE_SCALE_BYTES} whole, and never
     * less than one. A full chunk of {@link RaftNode#SNAPSHOT_CHUNK_SIZE} gets four. It is longer than a round trip
     * because the follower writes the chunk, and installs the snapshot with the last one, before it answers.
     */
    static Duration snapshotChunkDeadline(Duration roundTripDeadline, int bytes) {
        return roundTripDeadline.multipliedBy(Math.max(1, bytes / SNAPSHOT_DEADLINE_SCALE_BYTES));
    }

    /** The state of the connection to {@code peerName}, or {@code null} when the transport has none. */
    ConnectivityState connectionState(String peerName) {
        ManagedChannel channel = channels.get(peerName);
        return channel == null ? null : channel.getState(false);
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

    private <T> CompletableFuture<T> toFuture(ListenableFuture<T> listenableFuture) {
        CompletableFuture<T> promise = new CompletableFuture<>();
        Map<String, String> currentContext = MDC.getCopyOfContextMap();
        Map<String, String> capturedContext = currentContext == null ? Map.of() : new HashMap<>(currentContext);
        Context telemetryContext = Context.current();
        Futures.addCallback(listenableFuture, new FutureCallback<T>() {
            @Override
            public void onSuccess(T result) {
                runtime.execute(() -> promise.complete(result));
            }

            @Override
            public void onFailure(Throwable t) {
                runtime.execute(() -> promise.completeExceptionally(t));
            }
        }, command -> executor.execute(() -> withContext(capturedContext, telemetryContext, command)));
        return promise;
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
