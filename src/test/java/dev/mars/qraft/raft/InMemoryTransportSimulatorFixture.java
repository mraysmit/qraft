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
import dev.mars.qraft.raft.grpc.DescribeResponse;
import dev.mars.qraft.raft.grpc.InstallSnapshotRequest;
import dev.mars.qraft.raft.grpc.InstallSnapshotResponse;
import dev.mars.qraft.raft.grpc.JoinRequest;
import dev.mars.qraft.raft.grpc.MembershipResponse;
import dev.mars.qraft.raft.grpc.RemoveServerRequest;
import dev.mars.qraft.raft.grpc.VoteRequest;
import dev.mars.qraft.raft.grpc.VoteResponse;
import dev.mars.qraft.common.async.Future;
import dev.mars.qraft.common.async.Promise;
import dev.mars.qraft.testing.fault.InjectedFaultFixture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
/**
 * Test utility for in-memory Raft transport.
 * Allows Raft nodes to communicate without real network connections.
 * Supports advanced chaos testing including:
 * - Configurable latency and packet drop
 * - Network partitions (isolate nodes)
 * - Message reordering
 * - Bandwidth throttling
 * - Crash, slow, and flaky failure modes
 *
 * <p>No thread sleeps to simulate time: a request delayed by latency, throttling or reordering is held in a
 * queue and handed to the delivery pool when it falls due. Every request ends in a response or a failure;
 * stopping a transport fails whatever it still holds or has not yet started.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @version 3.0
 * @since 2026-01-05
 */

public class InMemoryTransportSimulatorFixture implements RaftTransport {

    private static final Logger logger = LoggerFactory.getLogger(InMemoryTransportSimulatorFixture.class);

    // Global registry of all transport instances
    private static final Map<String, InMemoryTransportSimulatorFixture> transports = new ConcurrentHashMap<>();
    /**
     * Sends and deliveries not yet finished, across every transport: queued or running on a pool, or held for
     * their latency. A test waits for it to reach zero rather than for a fixed time.
     */
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();

    // Network partition state (set of isolated node groups)
    private static final Set<Set<String>> networkPartitions = ConcurrentHashMap.newKeySet();

    private final String nodeId;
    // Use bounded thread pool (T3.2 consistency with production code)
    private final ExecutorService executor;
    private volatile Consumer<RaftMessage> messageHandler;
    private volatile boolean running = false;
    private boolean stopped; // guarded by this: a stopped transport never starts its delivery thread again
    private RaftNode raftNode;
    /** Serves joins and removals forwarded to this node, as its Raft port would; null serves none. */
    private volatile MembershipService membership;

    // Chaos Configuration
    private final Random random;
    private volatile int minLatencyMs = 5;
    private volatile int maxLatencyMs = 15;
    private volatile double dropRate = 0.0; // 0.0 to 1.0

    // Message Reordering Configuration
    private volatile boolean reorderingEnabled = false;
    private volatile double reorderProbability = 0.0; // 0.0 to 1.0
    private volatile int maxReorderDelayMs = 100;
    private final PriorityBlockingQueue<DelayedMessageHelper> messageQueue = new PriorityBlockingQueue<>();
    private volatile ScheduledExecutorService reorderExecutor;

    // Bandwidth Throttling Configuration
    private volatile boolean throttlingEnabled = false;
    private volatile long maxBytesPerSecond = Long.MAX_VALUE;
    private final AtomicLong bytesSentThisSecond = new AtomicLong(0);
    private volatile long lastResetTime = System.currentTimeMillis();

    // Failure Mode Configuration
    private volatile FailureMode failureMode = FailureMode.NONE;
    private volatile boolean crashed = false;

    /**
     * Failure modes for sophisticated chaos testing.
     */
    public enum FailureMode {
        NONE,           // Normal operation
        CRASH,          // Node crashes (stops responding)
        SLOW,           // Node responds very slowly
        FLAKY           // Node intermittently fails
    }

    /**
     * A transport whose chaos is seeded from {@code qraft.transport.seed} when set, and otherwise from the
     * node ID, so a run is repeatable; the seed is logged.
     */
    public InMemoryTransportSimulatorFixture(String nodeId) {
        this(nodeId, Long.getLong("qraft.transport.seed", nodeId.hashCode()));
    }

    public InMemoryTransportSimulatorFixture(String nodeId, long seed) {
        this.nodeId = nodeId;
        this.random = new Random(seed);
        this.executor = Executors.newFixedThreadPool(10,
                Thread.ofPlatform().daemon().name(threadPrefix() + "send-", 0).factory());
        logger.debug("In-memory transport for {} uses chaos seed {}", nodeId, seed);
    }

    /** The name every thread of this transport starts with. */
    String threadPrefix() {
        return "in-memory-transport-" + nodeId + "-";
    }

    /** Requests held for delayed (reordered) delivery. */
    int queuedMessages() {
        return messageQueue.size();
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public void setRaftNode(RaftNode node) {
        this.raftNode = node;
    }

    /**
     * Configure chaos parameters for this transport.
     * @param minLatencyMs minimum latency in milliseconds
     * @param maxLatencyMs maximum latency in milliseconds
     * @param dropRate probability of dropping a packet (0.0 to 1.0)
     */
    public void setChaosConfig(int minLatencyMs, int maxLatencyMs, double dropRate) {
        this.minLatencyMs = minLatencyMs;
        this.maxLatencyMs = maxLatencyMs;
        this.dropRate = dropRate;
    }

    /**
     * Enable message reordering with specified probability.
     * @param enabled whether reordering is enabled
     * @param reorderProbability probability that a message will be reordered (0.0 to 1.0)
     * @param maxReorderDelayMs maximum delay to apply to reordered messages
     */
    public void setReorderingConfig(boolean enabled, double reorderProbability, int maxReorderDelayMs) {
        this.reorderingEnabled = enabled;
        this.reorderProbability = reorderProbability;
        this.maxReorderDelayMs = maxReorderDelayMs;
    }

    /**
     * Enable bandwidth throttling.
     * @param enabled whether throttling is enabled
     * @param maxBytesPerSecond maximum bytes per second to transmit
     */
    public void setThrottlingConfig(boolean enabled, long maxBytesPerSecond) {
        this.throttlingEnabled = enabled;
        this.maxBytesPerSecond = maxBytesPerSecond;
    }

    /**
     * Set the failure mode for this transport.
     * @param mode the failure mode to use
     */
    public void setFailureMode(FailureMode mode) {
        this.failureMode = mode;
        if (mode == FailureMode.CRASH) {
            this.crashed = true;
        } else {
            this.crashed = false;
        }
    }

    /**
     * Recover from a crash failure mode.
     */
    public void recoverFromCrash() {
        this.crashed = false;
        if (this.failureMode == FailureMode.CRASH) {
            this.failureMode = FailureMode.NONE;
        }
    }

    /**
     * Create a network partition. Nodes in different partitions cannot communicate.
     * @param partition1 first partition of node IDs
     * @param partition2 second partition of node IDs
     */
    public static void createPartition(Set<String> partition1, Set<String> partition2) {
        networkPartitions.add(new HashSet<>(partition1));
        networkPartitions.add(new HashSet<>(partition2));
        logger.info("Created network partition: {} | {}", partition1, partition2);
    }

    /**
     * Heal all network partitions.
     */
    public static void healPartitions() {
        networkPartitions.clear();
        logger.info("Healed all network partitions");
    }

    /**
     * Check if two nodes can communicate (not partitioned).
     */
    private static boolean canCommunicate(String sourceId, String targetId) {
        if (networkPartitions.isEmpty()) {
            return true;
        }

        // Find which partitions the nodes belong to
        for (Set<String> partition : networkPartitions) {
            boolean sourceInPartition = partition.contains(sourceId);
            boolean targetInPartition = partition.contains(targetId);

            // If source is in partition and target is not, or vice versa, they cannot communicate
            if (sourceInPartition && !targetInPartition) {
                return false;
            }
            if (!sourceInPartition && targetInPartition) {
                return false;
            }
        }

        return true;
    }

    /**
     * Starts the thread that hands held deliveries to the pool when they fall due. It starts with the transport,
     * or with the first held delivery, so a transport that is never used holds no thread.
     */
    private synchronized void startReorderProcessor() {
        if (stopped) {
            return;
        }
        if (reorderExecutor == null || reorderExecutor.isShutdown()) {
            reorderExecutor = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name(threadPrefix() + "delivery").factory());
            reorderExecutor.scheduleAtFixedRate(this::processReorderedMessages,
                10, 10, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Process messages in the reorder queue.
     */
    private void processReorderedMessages() {
        long now = System.currentTimeMillis();
        DelayedMessageHelper message;

        while ((message = messageQueue.peek()) != null && message.deliveryTime <= now) {
            message = messageQueue.poll();
            if (message != null) {
                dispatch(message::deliver, message.failure);
                IN_FLIGHT.decrementAndGet(); // held until now; the dispatch above counts it from here
            }
        }
    }

    /**
     * The extra delay bandwidth throttling imposes on a message of {@code messageSize} bytes: none while the
     * current one-second window has room, otherwise until the window ends, when a new one begins.
     */
    private synchronized long throttleDelay(int messageSize) {
        if (!throttlingEnabled) {
            return 0;
        }
        long now = System.currentTimeMillis();
        if (now - lastResetTime >= 1000) {
            bytesSentThisSecond.set(0);
            lastResetTime = now;
        }
        if (bytesSentThisSecond.addAndGet(messageSize) <= maxBytesPerSecond) {
            return 0;
        }
        long delayMs = Math.max(0, 1000 - (now - lastResetTime));
        bytesSentThisSecond.set(0);
        lastResetTime = now + delayMs;
        return delayMs;
    }

    /** Runs a send on the delivery pool; a send the pool will not take fails its request. */
    private void dispatch(Runnable send, Consumer<Throwable> failure) {
        IN_FLIGHT.incrementAndGet();
        try {
            executor.execute(new TaskHelper(send, failure));
        } catch (RejectedExecutionException stopped) {
            IN_FLIGHT.decrementAndGet();
            failure.accept(new IllegalStateException("Transport stopped: " + nodeId, stopped));
        }
    }

    /** Holds a delivery until {@code delayMs} has passed; the reorder processor hands it to the pool. */
    private void deliverAfter(long delayMs, Runnable delivery, Consumer<Throwable> failure) {
        IN_FLIGHT.incrementAndGet();
        startReorderProcessor();
        messageQueue.offer(new DelayedMessageHelper(System.currentTimeMillis() + delayMs, delivery, failure));
    }

    /**
     * Whether any send or delivery, on any transport, has not yet finished. Once none has, every reply sent
     * so far has reached the node that asked, though the node may still be handling it on its state loop.
     */
    public static boolean hasMessagesInFlight() {
        return IN_FLIGHT.get() > 0;
    }

    @Override
    public void start(Consumer<RaftMessage> messageHandler) {
        this.messageHandler = messageHandler;
        startReorderProcessor();
        this.running = true;
        transports.put(nodeId, this);
        logger.info("Started in-memory transport for node: {}", nodeId);
    }

    @Override
    public void stop() {
        this.running = false;
        synchronized (this) {
            stopped = true;
        }
        transports.remove(nodeId);
        for (Runnable neverStarted : executor.shutdownNow()) {
            if (neverStarted instanceof TaskHelper task) {
                IN_FLIGHT.decrementAndGet();
                task.failure().accept(new IllegalStateException("Transport stopped before sending: " + nodeId));
            }
        }
        // Sends still running may hold a delivery; they finish before the delivery thread stops, and
        // whatever they held is failed below.
        awaitTermination(executor, "transport");
        ScheduledExecutorService delivery;
        synchronized (this) {
            delivery = reorderExecutor;
        }
        if (delivery != null) {
            delivery.shutdownNow();
        }
        awaitTermination(delivery, "reorder");
        DelayedMessageHelper held;
        while ((held = messageQueue.poll()) != null) {
            IN_FLIGHT.decrementAndGet();
            held.failure.accept(new IllegalStateException("Transport stopped before delivery: " + nodeId));
        }
        logger.info("Stopped in-memory transport for node: {}", nodeId);
    }

    private void awaitTermination(ExecutorService service, String executorName) {
        if (service == null) {
            return;
        }
        try {
            if (!service.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warn("Timed out draining in-memory {} executor for node: {}", executorName, nodeId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while draining in-memory {} executor for node: {}", executorName, nodeId);
        }
    }

    /** Serves joins and removals that reach this node through {@code membership}. */
    public void serveMembership(MembershipService membership) {
        this.membership = membership;
    }

    @Override
    public Future<MembershipResponse> join(String targetNodeId, JoinRequest request) {
        return membershipOf(targetNodeId).compose(target -> target.join(request));
    }

    @Override
    public Future<MembershipResponse> removeServer(String targetNodeId, RemoveServerRequest request) {
        return membershipOf(targetNodeId).compose(target -> target.remove(request));
    }

    private Future<MembershipService> membershipOf(String targetNodeId) {
        if (crashed || !canCommunicate(nodeId, targetNodeId)) {
            return Future.failedFuture(new RuntimeException("Cannot reach " + targetNodeId));
        }
        InMemoryTransportSimulatorFixture targetTransport = transports.get(targetNodeId);
        if (targetTransport == null || !targetTransport.running || targetTransport.crashed
                || targetTransport.membership == null) {
            return Future.failedFuture(new RuntimeException("Target node not available: " + targetNodeId));
        }
        return Future.succeededFuture(targetTransport.membership);
    }

    @Override
    public Future<DescribeResponse> describe(String targetNodeId) {
        if (crashed || !canCommunicate(nodeId, targetNodeId)) {
            return Future.failedFuture(new RuntimeException("Cannot reach " + targetNodeId));
        }
        InMemoryTransportSimulatorFixture targetTransport = transports.get(targetNodeId);
        if (targetTransport == null || !targetTransport.running || targetTransport.crashed
                || targetTransport.raftNode == null) {
            return Future.failedFuture(new RuntimeException("Target node not available: " + targetNodeId));
        }
        return targetTransport.raftNode.describe();
    }

    @Override
    public Future<VoteResponse> sendVoteRequest(String targetNodeId, VoteRequest request) {
        Promise<VoteResponse> promise = Promise.promise();
        dispatch(() -> {
            try {
                // Check if crashed
                if (crashed) {
                    promise.fail(new RuntimeException("Node crashed"));
                    return;
                }

                // Check for network partition
                if (!canCommunicate(nodeId, targetNodeId)) {
                    logger.debug("Network partition prevents communication from {} to {}", nodeId, targetNodeId);
                    promise.fail(new RuntimeException("Network partition"));
                    return;
                }

                // Simulate Packet Drop
                if (dropRate > 0 && random.nextDouble() < dropRate) {
                    logger.debug("Dropped VoteRequest from {} to {}", nodeId, targetNodeId);
                    promise.fail(new RuntimeException("Network packet dropped (Chaos)"));
                    return;
                }

                InMemoryTransportSimulatorFixture targetTransport = transports.get(targetNodeId);
                if (targetTransport == null || !targetTransport.running) {
                    promise.fail(new RuntimeException("Target node not available: " + targetNodeId));
                    return;
                }
                if (targetTransport.crashed) {
                    promise.fail(new RuntimeException("Target node crashed: " + targetNodeId));
                    return;
                }

                // Network delay from the failure mode, bandwidth throttling, and any reordering
                long delay = calculateDelay() + throttleDelay(request.getSerializedSize()) + reorderDelay();
                deliverAfter(delay, () -> {
                    VoteResponse response = targetTransport.handleVoteRequest(request);
                    logger.debug("Vote request from {} to {}: {}", nodeId, targetNodeId, response.getVoteGranted());
                    promise.complete(response);
                }, promise::fail);
            } catch (Exception e) {
                promise.fail(e);
            }
        }, promise::fail);
        return promise.future();
    }

    @Override
    public Future<AppendEntriesResponse> sendAppendEntries(String targetNodeId,
                                                                     AppendEntriesRequest request) {
        Promise<AppendEntriesResponse> promise = Promise.promise();
        dispatch(() -> {
            try {
                // Check if crashed
                if (crashed) {
                    promise.fail(new RuntimeException("Node crashed"));
                    return;
                }

                // Check for network partition
                if (!canCommunicate(nodeId, targetNodeId)) {
                    logger.debug("Network partition prevents communication from {} to {}", nodeId, targetNodeId);
                    promise.fail(new RuntimeException("Network partition"));
                    return;
                }

                // Simulate Packet Drop
                if (dropRate > 0 && random.nextDouble() < dropRate) {
                    logger.debug("Dropped AppendEntries from {} to {}", nodeId, targetNodeId);
                    promise.fail(new RuntimeException("Network packet dropped (Chaos)"));
                    return;
                }

                InMemoryTransportSimulatorFixture targetTransport = transports.get(targetNodeId);
                if (targetTransport == null || !targetTransport.running) {
                    promise.fail(new RuntimeException("Target node not available: " + targetNodeId));
                    return;
                }
                if (targetTransport.crashed) {
                    promise.fail(new RuntimeException("Target node crashed: " + targetNodeId));
                    return;
                }

                // Network delay from the failure mode, bandwidth throttling, and any reordering
                long delay = calculateDelay() + throttleDelay(request.getSerializedSize()) + reorderDelay();
                deliverAfter(delay, () -> {
                    AppendEntriesResponse response = targetTransport.handleAppendEntries(request);
                    logger.debug("Append entries from {} to {}: {}", nodeId, targetNodeId, response.getSuccess());
                    promise.complete(response);
                }, promise::fail);
            } catch (Exception e) {
                promise.fail(e);
            }
        }, promise::fail);
        return promise.future();
    }

    /** The extra delay of a reordered message, or none; reordering lets a later message overtake it. */
    private long reorderDelay() {
        if (reorderingEnabled && random.nextDouble() < reorderProbability) {
            return random.nextInt(maxReorderDelayMs);
        }
        return 0;
    }

    /**
     * Calculate delay based on current failure mode.
     */
    private long calculateDelay() {
        if (failureMode == FailureMode.SLOW) {
            // SLOW mode: 10x normal latency
            return (minLatencyMs + random.nextInt(Math.max(1, maxLatencyMs - minLatencyMs + 1))) * 10;
        } else if (failureMode == FailureMode.FLAKY && random.nextDouble() < 0.5) {
            // FLAKY mode: 50% chance of high latency
            return (minLatencyMs + random.nextInt(Math.max(1, maxLatencyMs - minLatencyMs + 1))) * 5;
        } else {
            // Normal latency
            return minLatencyMs + random.nextInt(Math.max(1, maxLatencyMs - minLatencyMs + 1));
        }
    }



    private VoteResponse handleVoteRequest(VoteRequest request) {
        if (raftNode != null) {
            return answer(raftNode.handleVoteRequest(request));
        }

        if (messageHandler != null) {
            messageHandler.accept(new RaftMessage.Vote(request));
        }

        logger.warn("RaftNode not set for transport {}, returning failure", nodeId);
        return VoteResponse.newBuilder()
                .setTerm(request.getTerm())
                .setVoteGranted(false)
                .build();
    }

    private AppendEntriesResponse handleAppendEntries(AppendEntriesRequest request) {
        if (raftNode != null) {
            return answer(raftNode.handleAppendEntriesRequest(request));
        }

        if (messageHandler != null) {
            messageHandler.accept(new RaftMessage.AppendEntries(request));
        }

        logger.warn("RaftNode not set for transport {}, returning failure", nodeId);
        return AppendEntriesResponse.newBuilder()
                .setTerm(request.getTerm())
                .setSuccess(false)
                .build();
    }

    private InstallSnapshotResponse handleInstallSnapshot(InstallSnapshotRequest request) {
        if (raftNode != null) {
            return answer(raftNode.handleInstallSnapshot(request));
        }

        logger.warn("RaftNode not set for transport {}, returning failure for InstallSnapshot", nodeId);
        return InstallSnapshotResponse.newBuilder()
                .setTerm(request.getTerm())
                .setSuccess(false)
                .setNextChunkIndex(0)
                .build();
    }

    @Override
    public Future<InstallSnapshotResponse> sendInstallSnapshot(String targetNodeId,
                                                                InstallSnapshotRequest request) {
        Promise<InstallSnapshotResponse> promise = Promise.promise();
        dispatch(() -> {
            try {
                // Check if crashed
                if (crashed) {
                    promise.fail(new RuntimeException("Node crashed"));
                    return;
                }

                // Check for network partition
                if (!canCommunicate(nodeId, targetNodeId)) {
                    logger.debug("Network partition prevents communication from {} to {}", nodeId, targetNodeId);
                    promise.fail(new RuntimeException("Network partition"));
                    return;
                }

                // Simulate Packet Drop
                if (dropRate > 0 && random.nextDouble() < dropRate) {
                    logger.debug("Dropped InstallSnapshot from {} to {}", nodeId, targetNodeId);
                    promise.fail(new RuntimeException("Network packet dropped (Chaos)"));
                    return;
                }

                InMemoryTransportSimulatorFixture targetTransport = transports.get(targetNodeId);
                if (targetTransport == null || !targetTransport.running) {
                    promise.fail(new RuntimeException("Target node not available: " + targetNodeId));
                    return;
                }
                if (targetTransport.crashed) {
                    promise.fail(new RuntimeException("Target node crashed: " + targetNodeId));
                    return;
                }

                deliverAfter(calculateDelay(), () -> {
                    InstallSnapshotResponse response = targetTransport.handleInstallSnapshot(request);
                    logger.debug("InstallSnapshot from {} to {}: success={}", nodeId, targetNodeId, response.getSuccess());
                    promise.complete(response);
                }, promise::fail);
            } catch (Exception e) {
                promise.fail(e);
            }
        }, promise::fail);
        return promise.future();
    }

    /** Waits a bounded time for the target node's answer; a node that never answers fails the request. */
    private static <T> T answer(Future<T> response) {
        try {
            return response.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            IllegalStateException failure = new IllegalStateException(
                    "Interrupted awaiting the target node", interrupted);
            failure.addSuppressed(new InjectedFaultFixture(
                    dev.mars.qraft.testing.fault.IntentionalErrorFixture.TRANSPORT_DELIVERY_INTERRUPTED,
                    "test teardown interrupted a delayed in-memory delivery"));
            throw failure;
        } catch (java.util.concurrent.ExecutionException failure) {
            throw failure.getCause() instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException(failure.getCause());
        } catch (java.util.concurrent.TimeoutException timeout) {
            throw new IllegalStateException("Target node did not answer within 10 s", timeout);
        }
    }

    public static Map<String, InMemoryTransportSimulatorFixture> getAllTransports() {
        return new ConcurrentHashMap<>(transports);
    }

    /**
     * Clear all registered transports (for testing).
     */
    public static void clearAllTransports() {
        transports.clear();
        healPartitions();
        // A count left by an earlier test must not make every later round wait out its deadline.
        IN_FLIGHT.set(0);
    }

    /**
     * Internal test scheduling helper that tracks work for the enclosing scheduler or transport.
     *
     * <p>A send or delivery on the pool, with the failure that ends its request if it never runs.
     */
    private record TaskHelper(Runnable body, Consumer<Throwable> failure) implements Runnable {
        @Override
        public void run() {
            try {
                body.run();
            } finally {
                IN_FLIGHT.decrementAndGet();
            }
        }
    }

    /**
     * Internal test transport helper that tracks queued message delivery.
     *
     * A delivery held until its time, by latency, throttling or reordering.
     */
    private static class DelayedMessageHelper implements Comparable<DelayedMessageHelper> {
        final long deliveryTime;
        final Runnable action;
        final Consumer<Throwable> failure;

        DelayedMessageHelper(long deliveryTime, Runnable action, Consumer<Throwable> failure) {
            this.deliveryTime = deliveryTime;
            this.action = action;
            this.failure = failure;
        }

        /** Delivers the request; a delivery that throws fails the request instead of leaving it waiting. */
        void deliver() {
            try {
                action.run();
            } catch (Exception e) {
                logger.debug("Delayed message delivery failed", e);
                failure.accept(e);
            }
        }

        @Override
        public int compareTo(DelayedMessageHelper other) {
            return Long.compare(this.deliveryTime, other.deliveryTime);
        }
    }
}
