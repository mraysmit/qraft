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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntToLongFunction;

/**
 * Publishes local check results to the controller as sequenced observations.
 *
 * <p>Each check has at most one publication in flight; results that arrive meanwhile are coalesced to
 * the latest. A changed status or output is published immediately, and an unchanged result is
 * republished as a renewal once half the check's TTL has passed since the last acceptance. Sequence
 * numbers are strictly increasing per check and start from the clock, so a restarted agent is not
 * rejected as stale; a stale answer raises the floor to the server's sequence and republishes.
 * Retries resend the same observation, so the server treats them as idempotent replays.
 *
 * <p>All state is guarded by this instance's lock. Client futures may complete on any thread.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
public final class HealthPublisher implements CheckResultListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(HealthPublisher.class);

    private final ObservationClient client;
    private final CheckScheduler scheduler;
    private final Clock clock;
    private final IntToLongFunction retryDelayMillis;
    private final Map<CheckKey, Publication> publications = new HashMap<>();
    private CompletableFuture<Void> stopped;

    /**
     * @param retryDelayMillis delay before retry number {@code n} (starting at zero) of an unconfirmed
     *                         observation after a retryable failure or an unregistered service
     */
    public HealthPublisher(ObservationClient client, CheckScheduler scheduler, Clock clock,
                           IntToLongFunction retryDelayMillis) {
        this.client = Objects.requireNonNull(client, "client");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retryDelayMillis = Objects.requireNonNull(retryDelayMillis, "retryDelayMillis");
    }

    @Override
    public synchronized void onResult(HealthCheckDefinition check, CheckResult result) {
        if (stopped != null) return;
        publications.computeIfAbsent(new CheckKey(check.serviceId(), check.checkId()),
                key -> new Publication(check)).offer(result);
    }

    /**
     * Refuses further results and cancels scheduled retries. The returned future completes once every
     * publication already in flight has finished; no observation is sent after this call.
     */
    public synchronized CompletableFuture<Void> stop() {
        if (stopped != null) return stopped;
        List<CompletableFuture<?>> inFlight = new ArrayList<>();
        for (Publication publication : publications.values()) {
            publication.cancelRetry();
            if (publication.inFlight != null) inFlight.add(publication.inFlight.handle((outcome, failure) -> null));
        }
        stopped = CompletableFuture.allOf(inFlight.toArray(CompletableFuture[]::new));
        return stopped;
    }

    /** Publication state for one check; accessed only under the publisher's lock. */
    private final class Publication {
        private final HealthCheckDefinition check;
        private long lastSequence;
        private CheckObservation accepted;
        private Instant acceptedAt;
        private CheckObservation unconfirmed;
        private CheckResult pending;
        private CompletableFuture<ObservationOutcome> inFlight;
        private CheckScheduler.Cancellable retry;
        private int retryAttempt;

        private Publication(HealthCheckDefinition check) {
            this.check = check;
        }

        void offer(CheckResult result) {
            if (inFlight != null) {
                pending = result;
                return;
            }
            publish(result);
        }

        private void publish(CheckResult result) {
            if (unconfirmed != null && unconfirmed.reports(result)) {
                cancelRetry();
                send(unconfirmed);
                return;
            }
            if (unconfirmed == null && accepted != null && accepted.reports(result) && !renewalDue()) return;
            cancelRetry();
            retryAttempt = 0;
            unconfirmed = next(result);
            send(unconfirmed);
        }

        private boolean renewalDue() {
            return !clock.instant().isBefore(acceptedAt.plus(check.ttl().dividedBy(2)));
        }

        private CheckObservation next(CheckResult result) {
            lastSequence = Math.max(lastSequence + 1, clock.millis());
            return CheckObservation.of(check, result, lastSequence);
        }

        private void send(CheckObservation observation) {
            CompletableFuture<ObservationOutcome> reply;
            try {
                reply = Objects.requireNonNull(client.observe(observation), "observation outcome");
            } catch (RuntimeException failure) {
                reply = CompletableFuture.completedFuture(new ObservationOutcome.Retryable(
                        "client_failure", Objects.toString(failure.getMessage(), failure.toString()), null));
            }
            inFlight = reply;
            reply.whenComplete((outcome, failure) -> completed(observation, failure == null ? outcome
                    : new ObservationOutcome.Retryable("client_failure", failure.toString(), null)));
        }

        private void completed(CheckObservation observation, ObservationOutcome outcome) {
            synchronized (HealthPublisher.this) {
                inFlight = null;
                if (stopped != null) return;
                boolean current = observation == unconfirmed;
                switch (outcome) {
                    case ObservationOutcome.Accepted ignored -> {
                        accepted = observation;
                        acceptedAt = clock.instant();
                        if (current) unconfirmed = null;
                        retryAttempt = 0;
                    }
                    case ObservationOutcome.Stale stale -> {
                        lastSequence = Math.max(lastSequence, stale.currentSequenceNumber());
                        if (current) {
                            unconfirmed = new CheckObservation(observation.serviceId(), observation.checkId(),
                                    observation.status(), ++lastSequence, observation.observedAt(),
                                    observation.ttl(), observation.required(), observation.output());
                            if (pending == null) send(unconfirmed);
                        }
                    }
                    case ObservationOutcome.Retryable ignored -> {
                        if (current) scheduleRetry();
                    }
                    case ObservationOutcome.Rejected rejected -> {
                        if (!current) break;
                        if ("service_not_found".equals(rejected.code())) {
                            scheduleRetry();
                        } else {
                            LOGGER.warn("Health observation rejected: service={}, check={}, sequence={}, code={}, "
                                            + "message={}", observation.serviceId(), observation.checkId(),
                                    observation.sequenceNumber(), rejected.code(), rejected.message());
                            unconfirmed = null;
                        }
                    }
                }
                if (pending != null && inFlight == null) {
                    CheckResult latest = pending;
                    pending = null;
                    publish(latest);
                }
            }
        }

        private void scheduleRetry() {
            cancelRetry();
            long delay = Math.max(1, retryDelayMillis.applyAsLong(retryAttempt++));
            retry = scheduler.schedule(this::retry, Duration.ofMillis(delay));
        }

        private void retry() {
            synchronized (HealthPublisher.this) {
                retry = null;
                if (stopped != null || inFlight != null || unconfirmed == null) return;
                send(unconfirmed);
            }
        }

        private void cancelRetry() {
            if (retry != null) retry.cancel();
            retry = null;
        }
    }

    private record CheckKey(String serviceId, String checkId) {
    }
}
