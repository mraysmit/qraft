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

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Owns the local execution of every configured check. Each check runs independently, so a slow or
 * failing check cannot delay checks for other services. Results go only to the injected listener.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
public final class LocalHealthChecks {
    private final List<HealthCheckRunner> runners = new ArrayList<>();
    private final Map<CheckKey, TtlCheckRunner> reporters = new LinkedHashMap<>();
    private boolean started;
    private boolean stopped;

    public LocalHealthChecks(List<HealthCheckDefinition> checks, HttpClient httpClient, TcpConnector tcpConnector,
                             CheckScheduler scheduler, Clock clock, CheckResultListener listener) {
        Objects.requireNonNull(checks, "checks");
        Objects.requireNonNull(httpClient, "httpClient");
        Objects.requireNonNull(tcpConnector, "tcpConnector");
        Map<CheckKey, HealthCheckDefinition> unique = new LinkedHashMap<>();
        for (HealthCheckDefinition check : checks) {
            CheckKey key = new CheckKey(check.serviceId(), check.checkId());
            if (unique.putIfAbsent(key, check) != null) {
                throw new IllegalArgumentException("Duplicate health check " + key.checkId()
                        + " for service " + key.serviceId());
            }
            switch (check) {
                case HttpCheck http -> runners.add(new ProbeCheckRunner(
                        http, new HttpProbe(http, httpClient), scheduler, clock, listener));
                case TcpCheck tcp -> runners.add(new ProbeCheckRunner(
                        tcp, new TcpProbe(tcp, tcpConnector), scheduler, clock, listener));
                case TtlCheck ttl -> {
                    TtlCheckRunner runner = new TtlCheckRunner(ttl, scheduler, clock, listener);
                    runners.add(runner);
                    reporters.put(key, runner);
                }
            }
        }
    }

    /** Starts every check once; a stopped instance cannot be restarted. */
    public synchronized void start() {
        if (started || stopped) return;
        started = true;
        runners.forEach(HealthCheckRunner::start);
    }

    /** Cancels scheduled and in-flight work for every check; no result is delivered afterwards. */
    public synchronized void stop() {
        if (stopped) return;
        stopped = true;
        runners.forEach(HealthCheckRunner::stop);
    }

    /** Returns the local status input for a TTL check, or empty for any other check. */
    public Optional<LocalStatusReporter> reporter(String serviceId, String checkId) {
        return Optional.ofNullable(reporters.get(new CheckKey(serviceId, checkId)));
    }

    private record CheckKey(String serviceId, String checkId) {
    }
}
