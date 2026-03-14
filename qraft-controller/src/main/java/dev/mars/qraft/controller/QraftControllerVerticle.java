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

package dev.mars.qraft.controller;

import dev.mars.qraft.controller.api.DistributedStateGrpcService;
import dev.mars.qraft.controller.config.AppConfig;
import dev.mars.qraft.controller.lifecycle.ShutdownCoordinator;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import dev.mars.qraft.controller.raft.RaftNode;
import dev.mars.qraft.controller.raft.RaftNodeMode;
import dev.mars.qraft.controller.raft.RaftTransport;
import dev.mars.qraft.controller.raft.GrpcServiceServer;
import dev.mars.qraft.controller.raft.GrpcRaftTransport;
import dev.mars.qraft.controller.raft.GrpcRaftServer;
import dev.mars.qraft.controller.raft.storage.RaftStorage;
import dev.mars.qraft.controller.raft.storage.RaftStorageFactory;
import dev.mars.qraft.controller.state.DistributedStateRaftCommandCodec;
import dev.mars.qraft.controller.state.GenericStateStore;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Main Verticle for the Qraft Controller.
 * Initializes the reactive stack, including Raft consensus and gRPC transport.
 * 
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @version 1.0
 * @since 2025-12-16
 */
public class QraftControllerVerticle extends AbstractVerticle {

    private static final Logger logger = LoggerFactory.getLogger(QraftControllerVerticle.class);

    private RaftTransport transport;
    private Optional<RaftNode> raftNode = Optional.empty();
    private RaftStorage raftStorage;
    private Optional<GrpcRaftServer> raftGrpcServer = Optional.empty();
    private Optional<GrpcServiceServer> apiGrpcServer = Optional.empty();
    private Optional<ShutdownCoordinator> shutdownCoordinator = Optional.empty();

    @Override
    public void start(Promise<Void> startPromise) throws Exception {
        logger.info("Starting QraftControllerVerticle...");

        try {
            // 1. Load configuration
            AppConfig config = AppConfig.get();
            String nodeId = config.getNodeId();

            // Set process-lifetime MDC context for all controller logs
            MDC.put("nodeId", nodeId);

            int raftPort = config.getRaftPort();
            int apiGrpcPort = config.getApiGrpcPort();
            String clusterNodesEnv = config.getClusterNodes();

            // 2. Parse cluster configuration
            Map<String, String> peerAddresses = new HashMap<>();
            Set<String> clusterNodeIds = new HashSet<>();
            for (String entry : clusterNodesEnv.split(",")) {
                String[] parts = entry.trim().split("=");
                if (parts.length == 2) {
                    String peerNodeId = parts[0].trim();
                    String peerAddress = parts[1].trim();
                    clusterNodeIds.add(peerNodeId);
                    if (!peerNodeId.equals(nodeId)) {
                        peerAddresses.put(peerNodeId, peerAddress);
                    }
                }
            }
            logger.info("Cluster configuration: nodeId={}, peers={}", nodeId, peerAddresses);

            // 3. Setup Raft Transport (gRPC)
            int raftPoolSize = config.getRaftIoPoolSize();
            int raftQueueSize = config.getRaftIoQueueSize();
            this.transport = new GrpcRaftTransport(vertx, nodeId, peerAddresses, raftPoolSize, raftQueueSize);

            // 4. Create Raft Storage (WAL)
            String storageType = config.getRaftStorageType();
            Path storagePath = Path.of(config.getRaftStoragePath());
            boolean fsyncEnabled = config.getRaftStorageFsync();
            
            logger.info("Initializing Raft storage: type={}, path={}, fsync={}", 
                       storageType, storagePath, fsyncEnabled);

            // Create storage via factory
            RaftStorageFactory.create(vertx, storageType, storagePath, fsyncEnabled)
                .onSuccess(storage -> {
                    this.raftStorage = storage;
                    continueStartup(startPromise, config, nodeId, raftPort, apiGrpcPort, clusterNodeIds);
                })
                .onFailure(err -> {
                    logger.error("Failed to initialize Raft storage: {}", err.getMessage());
                    logger.debug("Stack trace for Raft storage initialization failure", err);
                    startPromise.fail(err);
                });

        } catch (Exception e) {
            startPromise.fail(e);
        }
    }

    /**
     * Continues the startup sequence after storage is initialized.
     */
    private void continueStartup(Promise<Void> startPromise, AppConfig config,
                                 String nodeId, int raftPort, int apiGrpcPort,
                                 Set<String> clusterNodeIds) {
        try {
            // 5. Create Raft Node with storage
            Map<String, String> initialMetadata = new HashMap<>();
            initialMetadata.put("version", config.getVersion());

            GenericStateStore stateMachine = new GenericStateStore(initialMetadata);

            // Use the builder with storage and snapshot configuration
            RaftNode node = RaftNode.builder()
                    .vertx(vertx)
                    .nodeId(nodeId)
                    .clusterNodes(clusterNodeIds)
                    .transport(transport)
                    .stateMachine(stateMachine)
                    .commandCodec(new DistributedStateRaftCommandCodec())
                    .mode(RaftNodeMode.durable(raftStorage))
                    .electionTimeout(5000)
                    .heartbeatInterval(1000)
                    .snapshotEnabled(config.isSnapshotEnabled())
                    .snapshotThreshold(config.getSnapshotThreshold())
                    .snapshotCheckInterval(config.getSnapshotCheckIntervalMs())
                    .logHardLimit(config.getLogHardLimit())
                    .build();
            this.raftNode = Optional.of(node);

            transport.setRaftNode(node);

            // 6. Create and start separate gRPC servers: internal Raft RPC and external API RPC
            GrpcRaftServer internalRaftServer = new GrpcRaftServer(vertx, raftPort, node);
            this.raftGrpcServer = Optional.of(internalRaftServer);

            DistributedStateGrpcService distributedStateService = new DistributedStateGrpcService(node, stateMachine);
            GrpcServiceServer externalApiServer = new GrpcServiceServer(vertx, apiGrpcPort, distributedStateService);
            this.apiGrpcServer = Optional.of(externalApiServer);

            internalRaftServer.start().compose(v1 -> {
                logger.info("Internal Raft gRPC server started on port {}", raftPort);
                return externalApiServer.start();
            }).onSuccess(v2 -> {
                logger.info("External API gRPC server started on port {}", apiGrpcPort);

                // 7. Start Raft (includes recovery from WAL)
                node.start().onSuccess(v3 -> {
                    // 8. Setup shutdown coordinator for graceful shutdown
                    setupShutdownCoordinator();

                    logger.info("QraftControllerVerticle started successfully (split gRPC mode)");
                    startPromise.complete();
                }).onFailure(startPromise::fail);
            }).onFailure(startPromise::fail);

        } catch (Exception e) {
            startPromise.fail(e);
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
    private void setupShutdownCoordinator() {
        AppConfig config = AppConfig.get();
        long drainTimeoutMs = config.getLong("qraft.shutdown.drain.timeout.ms", 5000L);
        long shutdownTimeoutMs = config.getLong("qraft.shutdown.timeout.ms", 30000L);
        
        ShutdownCoordinator coordinator = new ShutdownCoordinator(vertx, drainTimeoutMs, shutdownTimeoutMs);
        this.shutdownCoordinator = Optional.of(coordinator);
        
        // No HTTP server in gRPC core mode.
        
        // Phase 2: AWAIT_COMPLETION - No active jobs tracked at controller level yet
        // (Agents track their own transfers - controller just routes requests)
        
        // Phase 3: STOP_SERVICES - Stop in reverse order of startup
        coordinator.onServiceStop("raft-node-stop", () -> {
            return raftNode.map(RaftNode::stop).orElseGet(Future::succeededFuture);
        });
        
        coordinator.onServiceStop("grpc-server-stop", () -> {
            Future<Void> raftStop = raftGrpcServer.map(GrpcRaftServer::stop).orElseGet(Future::succeededFuture);
            Future<Void> apiStop = apiGrpcServer.map(GrpcServiceServer::stop).orElseGet(Future::succeededFuture);
            return Future.all(raftStop, apiStop).mapEmpty();
        });
        
        // Phase 4: CLOSE_RESOURCES - Storage is closed by raftNode.stop()
        
        logger.info("Shutdown coordinator configured (drain={}ms, timeout={}ms)", 
                   drainTimeoutMs, shutdownTimeoutMs);
    }

    @Override
    public void stop(Promise<Void> stopPromise) throws Exception {
        logger.info("Stopping QraftControllerVerticle...");

        shutdownCoordinator.ifPresentOrElse(
            coordinator -> coordinator.shutdown()
                    .onSuccess(v -> {
                        logger.info("QraftControllerVerticle stopped successfully (graceful)");
                        stopPromise.complete();
                    })
                    .onFailure(err -> {
                        logger.warn("Error during graceful shutdown: {}", err.getMessage());
                        logger.debug("Stack trace for graceful shutdown failure", err);
                        // Still complete - we tried our best
                        stopPromise.complete();
                    }),
            () -> {
                // Fallback to immediate shutdown if coordinator wasn't initialized
                try {
                    Future<Void> raftStop = raftNode.map(RaftNode::stop).orElseGet(Future::succeededFuture);
                    Future<Void> internalGrpcStop = raftGrpcServer.map(GrpcRaftServer::stop).orElseGet(Future::succeededFuture);
                    Future<Void> externalGrpcStop = apiGrpcServer.map(GrpcServiceServer::stop).orElseGet(Future::succeededFuture);

                    Future.all(raftStop, internalGrpcStop, externalGrpcStop)
                            .onSuccess(v -> {
                                logger.info("QraftControllerVerticle stopped successfully (immediate)");
                                stopPromise.complete();
                            })
                            .onFailure(err -> {
                                logger.warn("Error during immediate shutdown: {}", err.getMessage());
                                logger.debug("Stack trace for immediate shutdown failure", err);
                                stopPromise.complete();
                            });
                } catch (Exception e) {
                    logger.warn("Error during shutdown: {}", e.getMessage());
                    logger.debug("Stack trace for shutdown error", e);
                    stopPromise.complete();
                }
            }
        );
    }
}
