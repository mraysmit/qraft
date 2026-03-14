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

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Generic gRPC server host for external API services.
 */
public class GrpcServiceServer {

    private static final Logger logger = LoggerFactory.getLogger(GrpcServiceServer.class);

    private final Vertx vertx;
    private final int port;
    private final BindableService[] services;
    private Server server;

    public GrpcServiceServer(Vertx vertx, int port, BindableService... services) {
        this.vertx = vertx;
        this.port = port;
        this.services = services != null ? services : new BindableService[0];
    }

    public Future<Void> start() {
        Promise<Void> promise = Promise.promise();

        vertx.executeBlocking(() -> {
            try {
                ServerBuilder<?> builder = ServerBuilder.forPort(port);
                for (BindableService service : services) {
                    builder.addService(service);
                }
                server = builder.build().start();
                logger.info("gRPC API server started on port {}", port);
                return null;
            } catch (IOException e) {
                throw new RuntimeException("Failed to start gRPC API server on port " + port, e);
            }
        }).onSuccess(v -> promise.complete())
          .onFailure(promise::fail);

        return promise.future();
    }

    public Future<Void> stop() {
        Promise<Void> promise = Promise.promise();

        if (server == null) {
            promise.complete();
            return promise.future();
        }

        vertx.executeBlocking(() -> {
            try {
                server.shutdown();
                if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                    server.shutdownNow();
                    if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                        logger.warn("gRPC API server did not terminate cleanly");
                    }
                }
                logger.info("gRPC API server stopped");
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                server.shutdownNow();
                throw new RuntimeException("Interrupted while stopping gRPC API server", e);
            }
        }).onSuccess(v -> promise.complete())
          .onFailure(promise::fail);

        return promise.future();
    }
}
