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

package dev.mars.qraft.client.catalog;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.mars.qraft.common.ServiceDefinition;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link ServiceReconciler} registration passes, retries, change detection, rejection
 * suppression, and shutdown deregistration using a fake {@link CatalogClient}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-24
 * @version 1.0
 */
class ServiceReconcilerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-24T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void registersEveryEnabledDefinitionAndSkipsDisabledDefinitions() {
        FakeCatalogClientFixture client = new FakeCatalogClientFixture();
        AtomicReference<List<ServiceDefinition>> definitions = new AtomicReference<>(List.of(
                service("web", "web", 8080, true), service("admin", "admin", 8081, false)));
        ServiceReconciler reconciler = new ServiceReconciler(client, definitions::get, CLOCK);

        ServiceReconciler.Result result = reconciler.trigger().join();

        assertEquals(List.of("web"), client.registrations);
        assertEquals(1, result.registered());
        assertTrue(result.rejections().isEmpty());
        assertTrue(reconciler.isConverged());
    }

    @Test
    void retainsPartialSuccessAndRetriesOnlyTheMissingDefinition() {
        FakeCatalogClientFixture client = new FakeCatalogClientFixture();
        client.registrationOutcomes.put("api", new CatalogOutcome.Retryable("offline", "later", null));
        List<ServiceDefinition> definitions = List.of(
                service("web", "web", 8080, true), service("api", "api", 8081, true));
        ServiceReconciler reconciler = new ServiceReconciler(client, () -> definitions, CLOCK);

        reconciler.trigger().join();
        assertFalse(reconciler.isConverged());
        client.registrationOutcomes.remove("api");
        client.lookups.put("web", new CatalogLookupOutcome.Present());
        reconciler.trigger().join();

        assertEquals(List.of("web", "api", "api"), client.registrations);
        assertTrue(reconciler.isConverged());
    }

    @Test
    void unchangedDefinitionIsReadButNotSentAgainAndChangedDefinitionIsReregisteredAlone() {
        FakeCatalogClientFixture client = new FakeCatalogClientFixture();
        AtomicReference<List<ServiceDefinition>> definitions = new AtomicReference<>(List.of(
                service("web", "web", 8080, true), service("api", "api", 8081, true)));
        ServiceReconciler reconciler = new ServiceReconciler(client, definitions::get, CLOCK);
        reconciler.trigger().join();
        client.lookups.put("web", new CatalogLookupOutcome.Present());
        client.lookups.put("api", new CatalogLookupOutcome.Present());

        reconciler.trigger().join();
        definitions.set(List.of(service("web", "web", 9090, true), service("api", "api", 8081, true)));
        reconciler.trigger().join();

        assertEquals(List.of("web", "api", "web"), client.registrations);
        assertEquals(List.of("web", "api", "api"), client.lookupIds);
    }

    @Test
    void aChangedCheckSetIsReRegisteredSoTheServerCanPruneRemovedChecks() {
        FakeCatalogClientFixture client = new FakeCatalogClientFixture();
        ServiceDefinition web = service("web", "web", 8080, true).withCheckIds(List.of("http", "tcp"));
        AtomicReference<List<ServiceDefinition>> definitions = new AtomicReference<>(List.of(web));
        ServiceReconciler reconciler = new ServiceReconciler(client, definitions::get, CLOCK);
        reconciler.trigger().join();
        client.lookups.put("web", new CatalogLookupOutcome.Present());

        definitions.set(List.of(web.withCheckIds(List.of("tcp"))));
        reconciler.trigger().join();
        definitions.set(List.of(web.withCheckIds(List.of())));
        reconciler.trigger().join();

        assertEquals(List.of("web", "web", "web"), client.registrations);
        assertEquals(ServiceReconciler.fingerprint(service("web", "web", 8080, true)),
                ServiceReconciler.fingerprint(web.withCheckIds(List.of())));
        assertNotEquals(ServiceReconciler.fingerprint(service("web", "web", 8080, true).withCheckIds(List.of())),
                ServiceReconciler.fingerprint(web.withCheckIds(List.of("a"))),
                "the check set is part of the definition fingerprint");
    }

    @Test
    void theFingerprintDoesNotConfuseTagsMetadataAndChecks() {
        assertNotEquals(fingerprint(List.of("env", "prod"), Map.of(), List.of()),
                fingerprint(List.of(), Map.of("env", "prod"), List.of()), "a tag pair is not a metadata entry");
        assertNotEquals(fingerprint(List.of("checks:1", "a"), Map.of(), List.of()),
                fingerprint(List.of(), Map.of(), List.of("a")), "tags are not declared checks");
        assertNotEquals(fingerprint(List.of(), Map.of("checks:1", "a"), List.of()),
                fingerprint(List.of(), Map.of(), List.of("a")), "metadata is not declared checks");
    }

    @Test
    void movingAValueFromTagsToMetadataIsReRegistered() {
        FakeCatalogClientFixture client = new FakeCatalogClientFixture();
        ServiceDefinition tagged = new ServiceDefinition("web", "web", "127.0.0.1", 8080,
                List.of("env", "prod"), Map.of(), true);
        AtomicReference<List<ServiceDefinition>> definitions = new AtomicReference<>(List.of(tagged));
        ServiceReconciler reconciler = new ServiceReconciler(client, definitions::get, CLOCK);
        reconciler.trigger().join();
        client.lookups.put("web", new CatalogLookupOutcome.Present());

        definitions.set(List.of(new ServiceDefinition("web", "web", "127.0.0.1", 8080,
                List.of(), Map.of("env", "prod"), true)));
        reconciler.trigger().join();

        assertEquals(List.of("web", "web"), client.registrations, "the changed definition is registered again");
    }

    @Test
    void theFingerprintIgnoresMetadataOrderButNotAnyField() {
        Map<String, String> ab = new java.util.LinkedHashMap<>();
        ab.put("a", "1");
        ab.put("b", "2");
        Map<String, String> ba = new java.util.LinkedHashMap<>();
        ba.put("b", "2");
        ba.put("a", "1");
        ServiceDefinition base = new ServiceDefinition("web", "web", "127.0.0.1", 8080, List.of("blue"), ab, true);
        assertEquals(ServiceReconciler.fingerprint(base), ServiceReconciler.fingerprint(
                new ServiceDefinition("web", "web", "127.0.0.1", 8080, List.of("blue"), ba, true)));

        for (ServiceDefinition changed : List.of(
                new ServiceDefinition("web-2", "web", "127.0.0.1", 8080, List.of("blue"), ab, true),
                new ServiceDefinition("web", "api", "127.0.0.1", 8080, List.of("blue"), ab, true),
                new ServiceDefinition("web", "web", "127.0.0.2", 8080, List.of("blue"), ab, true),
                new ServiceDefinition("web", "web", "127.0.0.1", 8081, List.of("blue"), ab, true),
                new ServiceDefinition("web", "web", "127.0.0.1", 8080, List.of("blue"), ab, false),
                new ServiceDefinition("web", "web", "127.0.0.1", 8080, List.of("green"), ab, true),
                new ServiceDefinition("web", "web", "127.0.0.1", 8080, List.of("blue"), Map.of("a", "9", "b", "2"),
                        true))) {
            assertNotEquals(ServiceReconciler.fingerprint(base), ServiceReconciler.fingerprint(changed),
                    changed.toString());
        }
    }

    private static String fingerprint(List<String> tags, Map<String, String> metadata, List<String> checks) {
        return ServiceReconciler.fingerprint(new ServiceDefinition("web", "web", "127.0.0.1", 8080, tags, metadata,
                true).withCheckIds(checks));
    }

    @Test
    void registersAgainWhenServerReportsTheInstanceAbsent() {
        FakeCatalogClientFixture client = new FakeCatalogClientFixture();
        ServiceDefinition web = service("web", "web", 8080, true);
        ServiceReconciler reconciler = new ServiceReconciler(client, () -> List.of(web), CLOCK);
        reconciler.trigger().join();
        client.lookups.put("web", new CatalogLookupOutcome.Absent());

        reconciler.trigger().join();

        assertEquals(List.of("web", "web"), client.registrations);
    }

    @Test
    void overlappingTriggersShareOnePass() {
        FakeCatalogClientFixture client = new FakeCatalogClientFixture();
        CompletableFuture<CatalogOutcome> pending = new CompletableFuture<>();
        client.pendingRegistration = pending;
        ServiceReconciler reconciler = new ServiceReconciler(client,
                () -> List.of(service("web", "web", 8080, true)), CLOCK);

        CompletableFuture<ServiceReconciler.Result> first = reconciler.trigger();
        CompletableFuture<ServiceReconciler.Result> second = reconciler.trigger();

        assertSame(first, second);
        assertEquals(1, client.registrations.size());
        pending.complete(new CatalogOutcome.Success("web", true));
        first.join();
        assertFalse(reconciler.isReconciling());
    }

    @Test
    void rejectedDefinitionIsReportedAndSuppressedUntilItsContentChanges() {
        FakeCatalogClientFixture client = new FakeCatalogClientFixture();
        client.registrationOutcomes.put("web",
                new CatalogOutcome.Rejected("invalid_registration", "bad address", null));
        AtomicReference<List<ServiceDefinition>> definitions = new AtomicReference<>(
                List.of(service("web", "web", 8080, true)));
        ServiceReconciler reconciler = new ServiceReconciler(client, definitions::get, CLOCK);

        Logger logger = (Logger) LoggerFactory.getLogger(ServiceReconciler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ServiceReconciler.Result first = reconciler.trigger().join();
            ServiceReconciler.Result second = reconciler.trigger().join();
            definitions.set(List.of(service("web", "web", 9090, true)));
            ServiceReconciler.Result third = reconciler.trigger().join();

            assertEquals("invalid_registration", first.rejections().get("web").code());
            assertEquals("invalid_registration", second.rejections().get("web").code());
            assertEquals("invalid_registration", third.rejections().get("web").code());
            assertEquals(List.of("web", "web"), client.registrations);
            assertEquals(2, appender.list.stream().filter(event ->
                    event.getFormattedMessage().contains("serviceId=web")
                            && event.getFormattedMessage().contains("invalid_registration")
                            && event.getFormattedMessage().contains("bad address")).count());
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void shutdownWaitsForAnActivePassThenDeregistersItsCommittedService() {
        FakeCatalogClientFixture client = new FakeCatalogClientFixture();
        CompletableFuture<CatalogOutcome> pending = new CompletableFuture<>();
        client.pendingRegistration = pending;
        ServiceReconciler reconciler = new ServiceReconciler(client,
                () -> List.of(service("web", "web", 8080, true)), CLOCK);

        CompletableFuture<ServiceReconciler.Result> pass = reconciler.trigger();
        CompletableFuture<ServiceReconciler.ShutdownResult> shutdown = reconciler.beginShutdown();

        assertFalse(shutdown.isDone());
        pending.complete(new CatalogOutcome.Success("web", true));

        assertEquals(1, pass.join().registered());
        assertTrue(shutdown.join().complete());
        assertEquals(List.of("web"), client.deregistrations);
    }

    @Test
    void shutdownReportsRejectedAndRetryableDeregistrations() {
        FakeCatalogClientFixture client = new FakeCatalogClientFixture();
        ServiceReconciler reconciler = new ServiceReconciler(client, () -> List.of(
                service("web", "web", 8080, true), service("api", "api", 8081, true)), CLOCK);
        reconciler.trigger().join();
        client.deregistrationOutcomes.put("web",
                new CatalogOutcome.Rejected("invalid_deregistration", "cannot remove", null));
        client.deregistrationOutcomes.put("api",
                new CatalogOutcome.Retryable("offline", "try later", null));

        ServiceReconciler.ShutdownResult result = reconciler.beginShutdown().join();

        assertFalse(result.complete());
        assertEquals(0, result.deregistered());
        assertEquals(Map.of("web", "invalid_deregistration: cannot remove",
                "api", "offline: try later"), result.failures());
    }

    @Test
    void lookupRetryableAndRejectedOutcomesLeaveCommittedStateUnchanged() {
        FakeCatalogClientFixture client = new FakeCatalogClientFixture();
        ServiceDefinition web = service("web", "web", 8080, true);
        ServiceReconciler reconciler = new ServiceReconciler(client, () -> List.of(web), CLOCK);
        reconciler.trigger().join();

        client.lookups.put("web", new CatalogLookupOutcome.Retryable("offline", "later", null));
        ServiceReconciler.Result retryable = reconciler.trigger().join();
        client.lookups.put("web", new CatalogLookupOutcome.Rejected("forbidden", "denied", null));
        ServiceReconciler.Result rejected = reconciler.trigger().join();

        assertTrue(retryable.rejections().isEmpty());
        assertEquals("forbidden", rejected.rejections().get("web").code());
        assertEquals(1, reconciler.registeredCount());
        assertEquals(List.of("web"), client.registrations);
    }

    @Test
    void removedDefinitionIsDeregisteredOnTheNextPass() {
        FakeCatalogClientFixture client = new FakeCatalogClientFixture();
        AtomicReference<List<ServiceDefinition>> definitions = new AtomicReference<>(
                List.of(service("web", "web", 8080, true)));
        ServiceReconciler reconciler = new ServiceReconciler(client, definitions::get, CLOCK);
        reconciler.trigger().join();
        definitions.set(List.of());

        reconciler.trigger().join();

        assertEquals(List.of("web"), client.deregistrations);
        assertEquals(0, reconciler.registeredCount());
    }

    @Test
    void shutdownStopsNewPassesAndDeregistersEveryKnownService() {
        FakeCatalogClientFixture client = new FakeCatalogClientFixture();
        List<ServiceDefinition> definitions = List.of(
                service("web", "web", 8080, true), service("api", "api", 8081, true));
        ServiceReconciler reconciler = new ServiceReconciler(client, () -> definitions, CLOCK);
        reconciler.trigger().join();

        ServiceReconciler.ShutdownResult result = reconciler.beginShutdown().join();
        reconciler.trigger().join();

        assertEquals(2, result.attempted());
        assertEquals(2, result.deregistered());
        assertTrue(result.failures().isEmpty());
        assertEquals(List.of("web", "api"), client.registrations);
        assertEquals(java.util.Set.of("web", "api"), java.util.Set.copyOf(client.deregistrations));
        assertEquals(0, reconciler.registeredCount());
    }

    private static ServiceDefinition service(String id, String name, int port, boolean enabled) {
        return new ServiceDefinition(id, name, "127.0.0.1", port,
                List.of("blue"), Map.of("team", "platform"), enabled);
    }

    /** Test catalog client fixture whose responses and recorded calls support reconciliation assertions. */
    private static final class FakeCatalogClientFixture implements CatalogClient {
        private final List<String> registrations = new ArrayList<>();
        private final List<String> deregistrations = new ArrayList<>();
        private final List<String> lookupIds = new ArrayList<>();
        private final Map<String, CatalogOutcome> registrationOutcomes = new LinkedHashMap<>();
        private final Map<String, CatalogOutcome> deregistrationOutcomes = new LinkedHashMap<>();
        private final Map<String, CatalogLookupOutcome> lookups = new LinkedHashMap<>();
        private CompletableFuture<CatalogOutcome> pendingRegistration;

        @Override
        public CompletableFuture<CatalogOutcome> register(ServiceDefinition service) {
            registrations.add(service.id());
            if (pendingRegistration != null) {
                CompletableFuture<CatalogOutcome> result = pendingRegistration;
                pendingRegistration = null;
                return result;
            }
            return CompletableFuture.completedFuture(registrationOutcomes.getOrDefault(service.id(),
                    new CatalogOutcome.Success(service.id(), true)));
        }

        @Override
        public CompletableFuture<CatalogOutcome> deregister(String serviceId) {
            deregistrations.add(serviceId);
            return CompletableFuture.completedFuture(deregistrationOutcomes.getOrDefault(serviceId,
                    new CatalogOutcome.Success(serviceId, true)));
        }

        @Override
        public CompletableFuture<CatalogLookupOutcome> lookup(ServiceDefinition service) {
            lookupIds.add(service.id());
            return CompletableFuture.completedFuture(lookups.getOrDefault(service.id(),
                    new CatalogLookupOutcome.Present()));
        }

        @Override public void close() { }
    }
}
