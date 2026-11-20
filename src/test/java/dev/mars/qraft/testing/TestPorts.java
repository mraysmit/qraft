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

package dev.mars.qraft.testing;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.HashSet;
import java.util.Set;

/**
 * Hands out ports for tests that must write a port into a configuration before the server that binds it starts.
 *
 * <p>A port is found by binding port 0 and closing the socket, so the kernel may offer it again before the server
 * binds it. Jenkins build 85 failed {@code KubernetesAuthEndToEndTest} so: port 35461 went to server1's Raft port, which
 * server1 had bound, and then to server2's API gRPC port. A port given out once in this JVM is therefore never given
 * out again, which covers every port a test in the same fork chose, bound or not. A port another process takes
 * between the choice and the bind is not covered; only a server that binds port 0 itself would be.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-20
 * @version 1.0
 */
public final class TestPorts {
    /** A source of candidate ports. */
    @FunctionalInterface
    interface PortSource {
        int next() throws IOException;
    }

    private static final int MAX_OFFERS = 100;
    private static final TestPorts SYSTEM = new TestPorts(TestPorts::ephemeralPort);

    private final PortSource source;
    private final Set<Integer> issued = new HashSet<>();

    TestPorts(PortSource source) {
        this.source = source;
    }

    /** A port for a server the test is about to configure. */
    public static int freePort() throws IOException {
        return SYSTEM.next();
    }

    synchronized int next() throws IOException {
        int offered = 0;
        for (int attempt = 0; attempt < MAX_OFFERS; attempt++) {
            offered = source.next();
            if (issued.add(offered)) return offered;
        }
        throw new IOException("No port not already given out in " + MAX_OFFERS + " offers; the last was " + offered);
    }

    private static int ephemeralPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
