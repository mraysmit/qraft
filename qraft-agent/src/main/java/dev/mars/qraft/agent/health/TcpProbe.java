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
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Opens and immediately closes one TCP connection to the configured address.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
final class TcpProbe implements Probe {
    private final TcpCheck check;
    private final TcpConnector connector;
    private final String target;

    TcpProbe(TcpCheck check, TcpConnector connector) {
        this.check = Objects.requireNonNull(check, "check");
        this.connector = Objects.requireNonNull(connector, "connector");
        this.target = check.host() + ":" + check.port();
    }

    @Override
    public CompletableFuture<Outcome> run() {
        CompletableFuture<Void> attempt;
        try {
            attempt = connector.connect(check.host(), check.port(), check.timeout());
        } catch (IOException | RuntimeException failure) {
            return CompletableFuture.completedFuture(failed(failure));
        }
        return Probe.linked(attempt, attempt.handle((ignored, failure) -> failure == null
                ? new Outcome(CheckStatus.PASSING, "TCP connect to " + target + " succeeded")
                : failed(failure)));
    }

    private Outcome failed(Throwable failure) {
        return new Outcome(CheckStatus.CRITICAL, "TCP connect to " + target + " failed: " + Probe.describe(failure));
    }
}
