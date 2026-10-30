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

package dev.mars.qraft.client.health;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the local execution of every check, from the configuration and from registrations made while the client runs.
 * Each check runs independently, so a slow or failing check cannot delay checks for other services. Results go only
 * to the injected listener, and the latest result of each check is kept for as long as the check is.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
public final class LocalHealthChecks {
    private final Map<CheckKey, Running> running = new LinkedHashMap<>();
    private final Map<CheckKey, CheckResult> latest = new ConcurrentHashMap<>();
    private final HttpClient httpClient;
    private final TcpConnector tcpConnector;
    private final CheckScheduler scheduler;
    private final Clock clock;
    private final CheckResultListener listener;
    private boolean started;
    private boolean stopped;

    public LocalHealthChecks(List<HealthCheckDefinition> checks, HttpClient httpClient, TcpConnector tcpConnector,
                             CheckScheduler scheduler, Clock clock, CheckResultListener listener) {
        Objects.requireNonNull(checks, "checks");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.tcpConnector = Objects.requireNonNull(tcpConnector, "tcpConnector");
        this.scheduler = scheduler;
        this.clock = clock;
        this.listener = (check, result) -> {
            latest.put(CheckKey.of(check), result);
            listener.onResult(check, result);
        };
        for (HealthCheckDefinition check : checks) {
            CheckKey key = CheckKey.of(check);
            if (running.containsKey(key)) {
                throw new IllegalArgumentException("Duplicate health check " + key.checkId()
                        + " for service " + key.serviceId());
            }
            running.put(key, runnerFor(check));
        }
    }

    private Running runnerFor(HealthCheckDefinition check) {
        return new Running(check, switch (check) {
            case HttpCheck http -> new ProbeCheckRunner(http, new HttpProbe(http, httpClient), scheduler, clock, listener);
            case TcpCheck tcp -> new ProbeCheckRunner(tcp, new TcpProbe(tcp, tcpConnector, clock), scheduler, clock,
                    listener);
            case TtlCheck ttl -> new TtlCheckRunner(ttl, scheduler, clock, listener);
        });
    }

    /** Starts every check once; a stopped instance cannot be restarted. */
    public synchronized void start() {
        if (started || stopped) return;
        started = true;
        running.values().forEach(check -> check.runner().start());
    }

    /** Cancels scheduled and in-flight work for every check; no result is delivered afterwards. */
    public synchronized void stop() {
        if (stopped) return;
        stopped = true;
        running.values().forEach(check -> check.runner().stop());
    }

    /**
     * Runs a check registered while the client runs: at once, or from {@link #start()} if that is still to come, and
     * never once stopped. A check with the same service and ID is replaced, unless its definition is the same: then
     * it goes on running and keeps its result, so that registering a service again disturbs nothing.
     */
    public synchronized void add(HealthCheckDefinition check) {
        if (stopped) return;
        CheckKey key = CheckKey.of(check);
        Running existing = running.get(key);
        if (existing != null && existing.definition().equals(check)) return;
        if (existing != null) remove(check);
        Running added = runnerFor(check);
        running.put(key, added);
        if (started) added.runner().start();
    }

    /**
     * Stops a check and forgets it and its result. A runner delivers its results under its own lock and delivers none
     * once stopped, so no result of this check can arrive after this returns.
     */
    public synchronized void remove(HealthCheckDefinition check) {
        CheckKey key = CheckKey.of(check);
        Running removed = running.remove(key);
        if (removed != null) removed.runner().stop();
        latest.remove(key);
    }

    /** The latest result of a check, or empty if it has none yet or is not running. */
    public Optional<CheckResult> latest(String serviceId, String checkId) {
        return Optional.ofNullable(latest.get(new CheckKey(serviceId, checkId)));
    }

    /** Returns the local status input for a TTL check, or empty for any other check. */
    public synchronized Optional<LocalStatusReporter> reporter(String serviceId, String checkId) {
        Running check = running.get(new CheckKey(serviceId, checkId));
        return check != null && check.runner() instanceof LocalStatusReporter reporter
                ? Optional.of(reporter) : Optional.empty();
    }

    private record Running(HealthCheckDefinition definition, HealthCheckRunner runner) {
    }

    private record CheckKey(String serviceId, String checkId) {
        static CheckKey of(HealthCheckDefinition check) {
            return new CheckKey(check.serviceId(), check.checkId());
        }
    }
}
