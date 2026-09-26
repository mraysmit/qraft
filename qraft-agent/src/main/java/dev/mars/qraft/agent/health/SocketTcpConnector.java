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

package dev.mars.qraft.agent.health;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Connects on a virtual thread so name resolution and connection setup never block the check
 * scheduler. Cancelling the attempt closes the socket.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
public final class SocketTcpConnector implements TcpConnector {
    @Override
    public CompletableFuture<Void> connect(String host, int port, Duration timeout) {
        CompletableFuture<Void> attempt = new CompletableFuture<>();
        Socket socket = new Socket();
        int timeoutMillis = (int) Math.clamp(timeout.toMillis(), 1, Integer.MAX_VALUE);
        attempt.whenComplete((ignored, failure) -> {
            if (attempt.isCancelled()) closeQuietly(socket);
        });
        Thread.ofVirtual().name("qraft-tcp-check").start(() -> {
            try (socket) {
                socket.connect(new InetSocketAddress(host, port), timeoutMillis);
                attempt.complete(null);
            } catch (IOException | RuntimeException failure) {
                attempt.completeExceptionally(failure);
            }
        });
        return attempt;
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // The attempt has already been abandoned.
        }
    }
}
