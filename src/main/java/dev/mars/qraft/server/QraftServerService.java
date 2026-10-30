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

package dev.mars.qraft.server;

import dev.mars.qraft.common.concurrent.Completions;
import dev.mars.qraft.common.concurrent.Deadlines;
import dev.mars.qraft.server.api.DistributedStateGrpcService;
import dev.mars.qraft.server.config.AppConfig;
import dev.mars.qraft.server.ui.UiAssets;
import dev.mars.qraft.server.health.ExpiryScheduler;
import dev.mars.qraft.server.health.LeaderHealthExpiry;
import dev.mars.qraft.server.health.NodeExpiryPolicy;
import dev.mars.qraft.server.lifecycle.ShutdownCoordinator;
import dev.mars.qraft.common.async.RaftStateLoop;
import dev.mars.qraft.common.async.BlockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.mars.qraft.raft.RaftNode;
import dev.mars.qraft.raft.RaftMembershipPolicy;
import dev.mars.qraft.raft.RaftNodeMode;
import dev.mars.qraft.raft.RaftTransport;
import dev.mars.qraft.raft.GrpcServiceServer;
import dev.mars.qraft.raft.GrpcRaftTransport;
import dev.mars.qraft.raft.ClusterBootstrap;
import dev.mars.qraft.raft.MembershipService;
import dev.mars.qraft.raft.GrpcRaftServer;
import dev.mars.qraft.raft.storage.RaftStorageFactory;
import dev.mars.qraft.raft.storage.ServerIdentity;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.qraft.state.QraftStateStore;
import dev.mars.qraft.server.http.HttpApiServer;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Main service host for the Qraft Server.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @version 1.0
 * @since 2025-12-16
 */
public class QraftServerService {

    private static final Logger logger = LoggerFactory.getLogger(QraftServerService.class);
    /** Bounds an expiry proposal, so a proposal that never completes cannot suppress re-evaluation. */
    private static final long HEALTH_EXPIRY_PROPOSAL_TIMEOUT_MS = 5_000;
    private static final long BOOTSTRAP_RETRY_MS = 1_000;

    private final RaftStateLoop runtime;
    private final BlockingTaskExecutor blockingTasks;
    private final AppConfig config;

    private RaftTransport transport;
    private Optional<RaftNode> raftNode = Optional.empty();
    private RaftStorageFactory.DurableStorage raftStorage;
    private Optional<GrpcRaftServer> raftGrpcServer = Optional.empty();
    private Optional<GrpcServiceServer> apiGrpcServer = Optional.empty();
    private Optional<HttpApiServer> httpApiServer = Optional.empty();
    private Optional<LeaderHealthExpiry> healthExpiry = Optional.empty();
    private Optional<ScheduledExecutorService> healthExpiryExecutor = Optional.empty();
    private Optional<ShutdownCoordinator> shutdownCoordinator = Optional.empty();
    private volatile Optional<Long> bootstrapTimer = Optional.empty();

    /** A server that runs on {@code runtime} with the configuration it is given; it reads no other. */
    public QraftServerService(RaftStateLoop runtime, BlockingTaskExecutor blockingTasks, AppConfig config) {
        this.runtime = java.util.Objects.requireNonNull(runtime, "stateLoop");
        this.blockingTasks = java.util.Objects.requireNonNull(blockingTasks, "blockingTasks");
        this.config = java.util.Objects.requireNonNull(config, "config");
    }

    public CompletableFuture<Void> start() {
        CompletableFuture<Void> promise = new CompletableFuture<>();
        start(promise);
        return promise;
    }

    public void start(CompletableFuture<Void> startPromise) {
        logger.info("Starting QraftServerService...");

        try {
            RaftMembershipPolicy membershipPolicy = new RaftMembershipPolicy(
                    config.getPromotionStabilizationMs(), config.getPromotionMaxTrailingEntries(),
                    config.isRaftCleanupEnabled(), config.getRaftCleanupFailureTimeoutMs(),
                    config.getRaftCleanupIntervalMs(), config.getRaftCleanupMinVoters());
            membershipPolicy.validateFor(config.getElectionTimeoutMs(), config.getHeartbeatIntervalMs());
            // 1. The configuration this server was started with
            String nodeId = config.getNodeId();

            int raftPort = config.getRaftPort();
            int apiGrpcPort = config.getApiGrpcPort();

            // 2. The cluster's members, and among them this server's peers
            Map<String, String> listedAddresses = new HashMap<>(config.getClusterMembers());
            Set<String> clusterNodeIds = new HashSet<>(listedAddresses.keySet());
            Map<String, String> peerAddresses = new HashMap<>(listedAddresses);
            peerAddresses.remove(nodeId);
            logger.info("Cluster configuration: nodeId={}, peers={}", nodeId, peerAddresses);

            // 3. Setup Raft Transport (gRPC)
            int raftPoolSize = config.getRaftIoPoolSize();
            int raftQueueSize = config.getRaftIoQueueSize();
            this.transport = new GrpcRaftTransport(runtime, nodeId, peerAddresses, raftPoolSize, raftQueueSize);

            // 4. Create Raft Storage (WAL)
            String storageType = config.getRaftStorageType();
            Path storagePath = Path.of(config.getRaftStoragePath());
            boolean fsyncEnabled = config.getRaftStorageFsync();
            
            logger.info("Initializing Raft storage: type={}, path={}, fsync={}", 
                       storageType, storagePath, fsyncEnabled);

            // Create storage via factory
            if (!"raftlog".equalsIgnoreCase(storageType) && !"wal".equalsIgnoreCase(storageType)) {
                startPromise.completeExceptionally(new IllegalArgumentException(
                        "Unsupported Raft storage type '" + storageType + "'; only the external WAL is supported"));
                return;
            }
            RaftStorageFactory.createDurable(storagePath, fsyncEnabled)
                .whenComplete(Completions.outcome(
                        storage -> {
                            this.raftStorage = storage;
                            continueStartup(startPromise, nodeId, raftPort, apiGrpcPort, clusterNodeIds,
                                    listedAddresses, membershipPolicy);
                        },
                        err -> {
                            logger.error("Failed to initialize Raft storage: {}", err.getMessage(), err);
                            startPromise.completeExceptionally(err);
                        }));

        } catch (Exception e) {
            startPromise.completeExceptionally(e);
        }
    }

    /**
     * Continues the startup sequence after storage is initialized.
     */
    private void continueStartup(CompletableFuture<Void> startPromise,
                                 String nodeId, int raftPort, int apiGrpcPort,
                                 Set<String> clusterNodeIds, Map<String, String> listedAddresses,
                                 RaftMembershipPolicy membershipPolicy) {
        try {
            // 5. Load the server's durable identity. The open WAL holds the data directory's lock, so no other
            //    process can create a competing identity there.
            String serverId = ServerIdentity.loadOrCreate(Path.of(config.getRaftStoragePath()));
            logger.info("Raft server identity: nodeId={}, serverId={}", nodeId, serverId);

            // 6. Create Raft Node with storage. Replicated state starts the same on every server: nothing of a
            //    server's own build or configuration is put into it.
            QraftStateStore stateMachine = new QraftStateStore();

            // Use the builder with storage and snapshot configuration
            RaftNode node = RaftNode.builder()
                    .stateLoop(runtime)
                    .blockingTasks(blockingTasks)
                    .nodeId(nodeId)
                    .serverId(serverId)
                    .clusterNodes(clusterNodeIds)
                    .addresses(listedAddresses)
                    .transport(transport)
                    .stateMachine(stateMachine)
                    .commandCodec(new ProtobufRaftCommandCodec())
                    .mode(RaftNodeMode.durable(raftStorage.wal(), raftStorage.snapshots()))
                    .electionTimeout(config.getElectionTimeoutMs())
                    .heartbeatInterval(config.getHeartbeatIntervalMs())
                    .membershipPolicy(membershipPolicy)
                    .snapshotEnabled(config.isSnapshotEnabled())
                    .snapshotThreshold(config.getSnapshotThreshold())
                    .snapshotCheckInterval(config.getSnapshotCheckIntervalMs())
                    .logHardLimit(config.getLogHardLimit())
                    .build();
            this.raftNode = Optional.of(node);
            RaftMembershipPolicy effective = node.membershipPolicy();
            long heartbeatMs = config.getHeartbeatIntervalMs();
            logger.info("Raft membership policy: promotionStabilizationMs={}, promotionMaxTrailingEntries={}, "
                            + "cleanup.enabled={}, cleanup.failureTimeoutMs={}, cleanup.intervalMs={}, "
                            + "cleanup.minVoters={}, promotionStabilizationRounds={}, cleanup.failureTimeoutRounds={}, "
                            + "cleanup.intervalRounds={}",
                    effective.promotionStabilizationMs(), effective.promotionMaxTrailingEntries(),
                    effective.cleanupEnabled(), effective.cleanupFailureTimeoutMs(), effective.cleanupIntervalMs(),
                    effective.cleanupMinVoters(), effective.promotionStabilizationRounds(heartbeatMs),
                    effective.cleanupFailureTimeoutRounds(heartbeatMs), effective.cleanupIntervalRounds(heartbeatMs));

            transport.setRaftNode(node);

            // 7. Create and start separate gRPC servers: internal Raft RPC and external API RPC
            // Joins and operator removals reach any server, over HTTP or the Raft port, and go to the leader.
            MembershipService membership = new MembershipService(node, transport,
                    config.getOperatorToken().orElse(null));
            if (config.getOperatorToken().isEmpty()) {
                logger.info("No server.operator.token is configured: Raft operator removals are refused");
            }
            GrpcRaftServer internalRaftServer = new GrpcRaftServer(blockingTasks, raftPort, node, membership);
            this.raftGrpcServer = Optional.of(internalRaftServer);

            DistributedStateGrpcService distributedStateService = new DistributedStateGrpcService(node, stateMachine);
            GrpcServiceServer externalApiServer = new GrpcServiceServer(blockingTasks, apiGrpcPort, distributedStateService);
            this.apiGrpcServer = Optional.of(externalApiServer);
            HttpApiServer healthServer = new HttpApiServer(config.getHttpPort(), node, stateMachine, Clock.systemUTC(),
                    config.getAdminUi(), UiAssets.forConfig(config.getAdminUi()), HttpApiServer.DEFAULT_RAFT_TIMEOUT,
                    membership);
            this.httpApiServer = Optional.of(healthServer);

            // Only the leader evaluates health-check deadlines; followers apply committed expiry commands.
            ScheduledExecutorService expiryExecutor = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().name("qraft-health-expiry").daemon(true).factory());
            this.healthExpiryExecutor = Optional.of(expiryExecutor);
            NodeExpiryPolicy nodePolicy = new NodeExpiryPolicy(Duration.ofMillis(config.getNodeTtlMs()),
                    Duration.ofMillis(config.getNodeReapAfterMs()));
            this.healthExpiry = Optional.of(LeaderHealthExpiry.attach(node, stateMachine::healthChecks,
                    () -> stateMachine.getNodes().values(), nodePolicy,
                    command -> Deadlines.bound(node.submitCommand(command),
                            HEALTH_EXPIRY_PROPOSAL_TIMEOUT_MS, TimeUnit.MILLISECONDS),
                    ExpiryScheduler.of(expiryExecutor), Clock.systemUTC(),
                    Duration.ofMillis(config.getHealthExpiryIntervalMs())));

            internalRaftServer.start().thenCompose(v1 -> {
                logger.info("Internal Raft gRPC server started on port {}", internalRaftServer.port());
                return externalApiServer.start();
            }).whenComplete(Completions.outcome(
                    v2 -> {
                logger.info("External API gRPC server started on port {}", externalApiServer.port());

                try {
                    healthServer.start();
                    node.start().whenComplete(Completions.outcome(v3 -> {
                        logger.info("HTTP health server started on port {}", healthServer.port());

                        // 8. Start Raft (includes recovery from WAL)
                        // 9. Setup shutdown coordinator for graceful shutdown
                        setupShutdownCoordinator();
                        // 10. A server with no configuration bootstraps a new cluster or waits to join one
                        startClusterBootstrap(node);

                        logger.info("QraftServerService started successfully (gRPC and HTTP health mode)");
                        startPromise.complete(null);
                    },
                    error -> {
                        if (node.isFenced()) {
                            logger.error("Raft recovery failed; node remains live but unready and will not "
                                            + "participate. Preserve the node directory for diagnosis, then "
                                            + "replace it from a healthy peer and restart: {}",
                                    error.getMessage(), error);
                            setupShutdownCoordinator();
                            startPromise.complete(null);
                        } else {
                            startPromise.completeExceptionally(error);
                        }
                    }));
                } catch (Exception e) {
                    startPromise.completeExceptionally(e);
                }
            },
                    startPromise::completeExceptionally));

        } catch (Exception e) {
            startPromise.completeExceptionally(e);
        }
    }

    /**
     * Configures the shutdown coordinator with graceful shutdown hooks.
     * 
     * <p>Shutdown sequence:
     * <ol>
    *   <li>DRAIN: No-op in gRPC core mode</li>
     *   <li>AWAIT: Wait for any active operations to complete</li>
    *   <li>STOP_SERVICES: Stop Raft node, gRPC server</li>
     *   <li>CLOSE_RESOURCES: Close storage and other resources</li>
     * </ol>
     */
    /**
     * Until the node has a configuration with its advertised address, attempts to bootstrap or rejoin at once and every
     * {@value #BOOTSTRAP_RETRY_MS} ms, one attempt at a time. It stops once the node is configured, whether this
     * server bootstrapped it or a leader of an existing cluster replicated it.
     */
    private void startClusterBootstrap(RaftNode node) {
        ClusterBootstrap bootstrap = new ClusterBootstrap(node, transport);
        AtomicBoolean attempting = new AtomicBoolean();
        Consumer<Long> attempt = ignored -> {
            if (!attempting.compareAndSet(false, true)) return;
            bootstrap.attempt().whenComplete((resultValue, resultError) -> {
                attempting.set(false);
                if (resultError != null) {
                    logger.warn("Cluster bootstrap attempt failed: {}", Completions.unwrap(resultError).getMessage());
                } else if (resultValue == ClusterBootstrap.Outcome.BOOTSTRAPPED
                        || resultValue == ClusterBootstrap.Outcome.CONFIGURED) {
                    logger.info("Cluster configuration established: {}", node.getConfiguration().orElse(null));
                    bootstrapTimer.ifPresent(runtime::cancelTimer);
                } else {
                    logger.info("Cluster bootstrap: {}", resultValue);
                }
            });
        };
        bootstrapTimer = Optional.of(runtime.setPeriodic(BOOTSTRAP_RETRY_MS, attempt));
        attempt.accept(0L);
    }

    private void setupShutdownCoordinator() {
        long drainTimeoutMs = config.getShutdownDrainTimeoutMs();
        long shutdownTimeoutMs = config.getShutdownTimeoutMs();
        
        ShutdownCoordinator coordinator = new ShutdownCoordinator(runtime, drainTimeoutMs, shutdownTimeoutMs);
        this.shutdownCoordinator = Optional.of(coordinator);
        
        // Phase 2: AWAIT_COMPLETION - no server-managed work remains after draining.
        
        // Phase 3: STOP_SERVICES - Stop in reverse order of startup
        coordinator.onServiceStop("health-expiry-stop", () -> {
            stopHealthExpiry();
            return CompletableFuture.completedFuture(null);
        });
        coordinator.onServiceStop("grpc-server-stop", () -> {
            CompletableFuture<Void> raftStop = raftGrpcServer.map(GrpcRaftServer::stop)
                    .orElseGet(QraftServerService::nothingToStop);
            CompletableFuture<Void> apiStop = apiGrpcServer.map(GrpcServiceServer::stop)
                    .orElseGet(QraftServerService::nothingToStop);
            httpApiServer.ifPresent(HttpApiServer::stop);
            return CompletableFuture.allOf(raftStop, apiStop);
        });

        coordinator.onCriticalServiceStop("raft-node-stop", () -> {
            return raftNode.map(RaftNode::stop).orElseGet(QraftServerService::nothingToStop);
        });
        
        // Phase 4: CLOSE_RESOURCES - Storage is closed by raftNode.stop()
        
        logger.info("Shutdown coordinator configured (drain={}ms, timeout={}ms)", 
                   drainTimeoutMs, shutdownTimeoutMs);
    }

    private void stopHealthExpiry() {
        healthExpiry.ifPresent(LeaderHealthExpiry::close);
        healthExpiryExecutor.ifPresent(ScheduledExecutorService::shutdownNow);
    }

    /**
     * The ports this server's listeners bound: {@code http}, {@code raft}, and {@code apiGrpc}. A port
     * configured as 0 is reported as the port the system chose.
     *
     * @throws IllegalStateException when the server has not started its listeners, or has stopped them
     */
    public Map<String, Integer> boundPorts() {
        Map<String, Integer> ports = new java.util.HashMap<>();
        boundAddresses().forEach((listener, address) -> ports.put(listener, address.getPort()));
        return Map.copyOf(ports);
    }

    /**
     * The addresses this server's listeners bound: {@code http}, {@code raft}, and {@code apiGrpc}.
     *
     * @throws IllegalStateException when the server has not started its listeners, or has stopped them
     */
    public Map<String, java.net.InetSocketAddress> boundAddresses() {
        HttpApiServer http = httpApiServer.orElseThrow(() -> new IllegalStateException("the server is not running"));
        GrpcRaftServer raft = raftGrpcServer.orElseThrow(() -> new IllegalStateException("the server is not running"));
        GrpcServiceServer api = apiGrpcServer.orElseThrow(() -> new IllegalStateException("the server is not running"));
        return Map.of("http", http.address(), "raft", raft.address(), "apiGrpc", api.address());
    }

    /** Effective policy of the constructed Raft node, for startup diagnostics and lifecycle inspection. */
    public RaftMembershipPolicy membershipPolicy() {
        return raftNode.orElseThrow(() -> new IllegalStateException("the Raft node has not been constructed"))
                .getMembershipPolicy();
    }

    public CompletableFuture<Void> stop() {
        CompletableFuture<Void> promise = new CompletableFuture<>();
        stop(promise);
        return promise;
    }

    private static CompletableFuture<Void> nothingToStop() {
        return CompletableFuture.completedFuture(null);
    }

    public void stop(CompletableFuture<Void> stopPromise) {
        logger.info("Stopping QraftServerService...");
        bootstrapTimer.ifPresent(runtime::cancelTimer);

        shutdownCoordinator.ifPresentOrElse(
            coordinator -> coordinator.shutdown().whenComplete(Completions.outcome(
                    v -> {
                        logger.info("QraftServerService stopped successfully (graceful)");
                        stopPromise.complete(null);
                    },
                    err -> {
                        logger.error("Graceful shutdown could not complete safely: {}", err.getMessage(), err);
                        stopPromise.completeExceptionally(err);
                    })),
            () -> {
                // Fallback to immediate shutdown if coordinator wasn't initialized
                stopHealthExpiry();
                try {
                    CompletableFuture<Void> raftStop = raftNode.map(RaftNode::stop)
                            .orElseGet(QraftServerService::nothingToStop);
                    CompletableFuture<Void> internalGrpcStop = raftGrpcServer.map(GrpcRaftServer::stop)
                            .orElseGet(QraftServerService::nothingToStop);
                    CompletableFuture<Void> externalGrpcStop = apiGrpcServer.map(GrpcServiceServer::stop)
                            .orElseGet(QraftServerService::nothingToStop);
                    httpApiServer.ifPresent(HttpApiServer::stop);

                    CompletableFuture.allOf(raftStop, internalGrpcStop, externalGrpcStop)
                            .whenComplete(Completions.outcome(
                                    v -> {
                                        logger.info("QraftServerService stopped successfully (immediate)");
                                        stopPromise.complete(null);
                                    },
                                    err -> {
                                        logger.warn("Error during immediate shutdown: {}", err.getMessage(), err);
                                        stopPromise.completeExceptionally(err);
                                    }));
                } catch (Exception e) {
                    logger.warn("Error during shutdown: {}", e.getMessage(), e);
                    stopPromise.completeExceptionally(e);
                }
            }
        );
    }
}
