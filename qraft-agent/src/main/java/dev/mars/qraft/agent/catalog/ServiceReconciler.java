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

package dev.mars.qraft.agent.catalog;

import dev.mars.qraft.catalog.ServiceDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * Single-flight convergence of local service definitions into the replicated catalog.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-24
 * @version 1.0
 */
public final class ServiceReconciler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceReconciler.class);
    private final CatalogClient client;
    private final Supplier<List<ServiceDefinition>> definitions;
    private final Clock clock;
    private final Map<String, String> registered = new ConcurrentHashMap<>();
    private final Map<String, Rejection> rejected = new ConcurrentHashMap<>();
    private CompletableFuture<Result> inFlight;
    private CompletableFuture<ShutdownResult> shutdown;
    private boolean acceptingTriggers = true;
    private volatile Instant lastSuccessfulContact;

    public ServiceReconciler(CatalogClient client, Supplier<List<ServiceDefinition>> definitions, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Returns the current pass when a trigger overlaps an unfinished reconciliation. */
    public synchronized CompletableFuture<Result> trigger() {
        if (!acceptingTriggers) {
            return CompletableFuture.completedFuture(new Result(
                    0, 0, Map.of(), lastSuccessfulContact));
        }
        if (inFlight != null) return inFlight;
        CompletableFuture<Result> pass = reconcile(List.copyOf(definitions.get()));
        inFlight = pass;
        pass.whenComplete((ignored, failure) -> clear(pass));
        return pass;
    }

    private synchronized void clear(CompletableFuture<Result> pass) {
        if (inFlight == pass) inFlight = null;
    }

    private CompletableFuture<Result> reconcile(List<ServiceDefinition> desiredDefinitions) {
        Map<String, ServiceDefinition> desired = new LinkedHashMap<>();
        desiredDefinitions.stream().filter(ServiceDefinition::enabled)
                .forEach(definition -> desired.put(definition.id(), definition));
        Pass pass = new Pass();
        CompletableFuture<Void> sequence = CompletableFuture.completedFuture(null);

        for (String serviceId : List.copyOf(registered.keySet())) {
            if (!desired.containsKey(serviceId)) {
                sequence = sequence.thenCompose(ignored -> deregister(serviceId, pass));
            }
        }
        for (ServiceDefinition definition : desired.values()) {
            sequence = sequence.thenCompose(ignored -> reconcile(definition, pass));
        }
        return sequence.thenApply(ignored -> new Result(pass.registered, pass.deregistered,
                Map.copyOf(pass.rejections), lastSuccessfulContact));
    }

    private CompletableFuture<Void> deregister(String serviceId, Pass pass) {
        return client.deregister(serviceId).thenAccept(outcome -> {
            if (outcome instanceof CatalogOutcome.Success) {
                registered.remove(serviceId);
                rejected.remove(serviceId);
                pass.deregistered++;
                contacted();
            } else if (outcome instanceof CatalogOutcome.Rejected failure) {
                pass.rejections.put(serviceId, failure);
            }
        });
    }

    private CompletableFuture<Void> reconcile(ServiceDefinition definition, Pass pass) {
        String fingerprint = fingerprint(definition);
        Rejection previousRejection = rejected.get(definition.id());
        if (previousRejection != null && previousRejection.fingerprint.equals(fingerprint)) {
            pass.rejections.put(definition.id(), previousRejection.outcome);
            return CompletableFuture.completedFuture(null);
        }
        if (previousRejection != null) rejected.remove(definition.id());

        if (!fingerprint.equals(registered.get(definition.id()))) {
            return register(definition, fingerprint, pass);
        }
        return client.lookup(definition).thenCompose(outcome -> {
            if (outcome instanceof CatalogLookupOutcome.Present) {
                contacted();
                return CompletableFuture.completedFuture(null);
            }
            if (outcome instanceof CatalogLookupOutcome.Absent) {
                contacted();
                registered.remove(definition.id());
                return register(definition, fingerprint, pass);
            }
            if (outcome instanceof CatalogLookupOutcome.Rejected failure) {
                pass.rejections.put(definition.id(), new CatalogOutcome.Rejected(
                        failure.code(), failure.message(), failure.leaderId()));
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    private CompletableFuture<Void> register(ServiceDefinition definition, String fingerprint, Pass pass) {
        return client.register(definition).thenAccept(outcome -> {
            if (outcome instanceof CatalogOutcome.Success) {
                registered.put(definition.id(), fingerprint);
                rejected.remove(definition.id());
                pass.registered++;
                contacted();
            } else if (outcome instanceof CatalogOutcome.Rejected failure) {
                Rejection rejection = new Rejection(fingerprint, failure);
                rejected.put(definition.id(), rejection);
                pass.rejections.put(definition.id(), failure);
                LOGGER.warn("Service registration rejected: serviceId={}, code={}, message={}",
                        definition.id(), failure.code(), failure.message());
            }
        });
    }

    private void contacted() { lastSuccessfulContact = clock.instant(); }

    public synchronized boolean isReconciling() { return inFlight != null; }
    public int registeredCount() { return registered.size(); }
    public Instant lastSuccessfulContact() { return lastSuccessfulContact; }

    /** True when every currently enabled definition has its exact content committed. */
    public boolean isConverged() {
        for (ServiceDefinition definition : definitions.get()) {
            if (definition.enabled()
                    && !fingerprint(definition).equals(registered.get(definition.id()))) return false;
        }
        return true;
    }

    /** Stops new passes, waits for the current pass, then removes every service committed by this process. */
    public synchronized CompletableFuture<ShutdownResult> beginShutdown() {
        if (shutdown != null) return shutdown;
        acceptingTriggers = false;
        CompletableFuture<?> current = inFlight == null
                ? CompletableFuture.completedFuture(null)
                : inFlight.handle((ignored, failure) -> null);
        shutdown = current.thenCompose(ignored -> deregisterKnownServices());
        return shutdown;
    }

    private CompletableFuture<ShutdownResult> deregisterKnownServices() {
        List<String> serviceIds = registered.keySet().stream().sorted().toList();
        List<CompletableFuture<Deregistration>> attempts = serviceIds.stream()
                .map(this::deregisterForShutdown).toList();
        return CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    int succeeded = 0;
                    Map<String, String> failures = new LinkedHashMap<>();
                    for (CompletableFuture<Deregistration> attempt : attempts) {
                        Deregistration result = attempt.join();
                        if (result.success()) succeeded++;
                        else failures.put(result.serviceId(), result.failure());
                    }
                    return new ShutdownResult(serviceIds.size(), succeeded, Map.copyOf(failures));
                });
    }

    private CompletableFuture<Deregistration> deregisterForShutdown(String serviceId) {
        final CompletableFuture<CatalogOutcome> request;
        try {
            request = client.deregister(serviceId);
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(new Deregistration(
                    serviceId, false, message(failure)));
        }
        return request.handle((outcome, failure) -> {
            if (failure != null) return new Deregistration(serviceId, false, message(failure));
            if (outcome instanceof CatalogOutcome.Success) {
                registered.remove(serviceId);
                rejected.remove(serviceId);
                return new Deregistration(serviceId, true, null);
            }
            if (outcome instanceof CatalogOutcome.Rejected rejectedOutcome) {
                return new Deregistration(serviceId, false,
                        rejectedOutcome.code() + ": " + rejectedOutcome.message());
            }
            CatalogOutcome.Retryable retryable = (CatalogOutcome.Retryable) outcome;
            return new Deregistration(serviceId, false,
                    retryable.code() + ": " + retryable.message());
        });
    }

    private static String message(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
        return Objects.toString(cause.getMessage(), cause.getClass().getSimpleName());
    }

    static String fingerprint(ServiceDefinition definition) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, definition.id());
        append(canonical, definition.name());
        append(canonical, definition.address());
        append(canonical, Integer.toString(definition.port()));
        append(canonical, Boolean.toString(definition.enabled()));
        definition.tags().forEach(value -> append(canonical, value));
        new TreeMap<>(definition.metadata()).forEach((key, value) -> {
            append(canonical, key);
            append(canonical, value);
        });
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value).append(';');
    }

    public record Result(int registered, int deregistered,
                         Map<String, CatalogOutcome.Rejected> rejections,
                         Instant lastSuccessfulContact) { }

    public record ShutdownResult(int attempted, int deregistered, Map<String, String> failures) {
        public boolean complete() { return attempted == deregistered && failures.isEmpty(); }
    }

    private record Rejection(String fingerprint, CatalogOutcome.Rejected outcome) { }
    private record Deregistration(String serviceId, boolean success, String failure) { }
    private static final class Pass {
        private int registered;
        private int deregistered;
        private final Map<String, CatalogOutcome.Rejected> rejections = new LinkedHashMap<>();
    }
}
