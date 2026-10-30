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

package dev.mars.qraft.runtime;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The single completion and shutdown boundary for a launched Qraft mode.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-24
 * @version 1.0
 */
public interface RuntimeLifecycle extends AutoCloseable {
    CompletableFuture<Void> completion();

    CompletableFuture<Void> closeAsync();

    /**
     * The addresses this runtime listens on, by listener: {@code http} in both modes, and {@code raft} and
     * {@code apiGrpc} for a server. Each is the address the listener bound, the loopback address for a client's
     * local API unless configured otherwise, and a port configured as 0 is reported as the port the system chose.
     *
     * @throws IllegalStateException when the runtime is no longer listening
     */
    Map<String, InetSocketAddress> boundAddresses();

    /**
     * The ports of {@link #boundAddresses()}, by listener.
     *
     * @throws IllegalStateException when the runtime is no longer listening
     */
    default Map<String, Integer> boundPorts() {
        Map<String, Integer> ports = new HashMap<>();
        boundAddresses().forEach((listener, address) -> ports.put(listener, address.getPort()));
        return Map.copyOf(ports);
    }

    @Override
    default void close() {
        closeAsync().join();
    }
}
