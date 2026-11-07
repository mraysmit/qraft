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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.mars.qraft.common.ServiceDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests a sync the servers refuse for its token (ACL list, proposal 13, Phase 6): as Consul's agent, a 403 on a
 * service or a removal is a warning, the service counts as synced, so it does not hold readiness back, and it is
 * tried again at the next full pass, when a replaced token may have mended it.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
class ServiceReconcilerAclRefusalTest {
    private static final ServiceDefinition WEB = new ServiceDefinition("web-1", "web", "10.0.0.1", 80, List.of(),
            Map.of(), true);

    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(ServiceReconciler.class);

    @BeforeEach
    void capture() {
        captured.start();
        logger.addAppender(captured);
    }

    @AfterEach
    void release() {
        logger.detachAppender(captured);
    }

    @Test
    void aRefusedRegistrationIsAWarningCountsAsSyncedAndIsTriedAgainAtTheNextPass() {
        RefusingCatalog catalog = new RefusingCatalog();
        catalog.refuseRegistration = true;
        ServiceReconciler reconciler = new ServiceReconciler(catalog, () -> List.of(WEB), Clock.systemUTC(),
                () -> true);

        reconciler.trigger().join();

        assertTrue(reconciler.isConverged(), "a refused sync does not hold readiness back");
        assertTrue(captured.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                && event.getFormattedMessage().contains("web-1")
                && event.getFormattedMessage().contains("permission_denied")), captured.list.toString());
        catalog.refuseRegistration = false;
        reconciler.trigger().join();
        assertEquals(List.of("web-1", "web-1"), catalog.registrations, "tried again at the next pass");
    }

    @Test
    void aRefusedRemovalIsAWarningAndIsTriedAgainAtTheNextPass() {
        RefusingCatalog catalog = new RefusingCatalog();
        catalog.listed = Optional.of(Set.of("planted"));
        catalog.refuseRemoval = true;
        ServiceReconciler reconciler = new ServiceReconciler(catalog, List::of, Clock.systemUTC(), () -> true);

        reconciler.trigger().join();
        reconciler.trigger().join();

        assertEquals(List.of("planted", "planted"), catalog.deregistrations);
        assertTrue(captured.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                && event.getFormattedMessage().contains("planted")), captured.list.toString());
    }

    private static final class RefusingCatalog implements CatalogClient {
        private final List<String> registrations = new ArrayList<>();
        private final List<String> deregistrations = new ArrayList<>();
        private volatile boolean refuseRegistration;
        private volatile boolean refuseRemoval;
        private volatile Optional<Set<String>> listed = Optional.of(Set.of());

        @Override
        public CompletableFuture<CatalogOutcome> register(ServiceDefinition service) {
            registrations.add(service.id());
            return CompletableFuture.completedFuture(refuseRegistration ? denied()
                    : new CatalogOutcome.Success(service.id(), true));
        }

        @Override
        public CompletableFuture<CatalogOutcome> deregister(String serviceId) {
            deregistrations.add(serviceId);
            return CompletableFuture.completedFuture(refuseRemoval ? denied()
                    : new CatalogOutcome.Success(serviceId, true));
        }

        @Override
        public CompletableFuture<CatalogLookupOutcome> lookup(ServiceDefinition service) {
            return CompletableFuture.completedFuture(new CatalogLookupOutcome.Present());
        }

        @Override
        public CompletableFuture<Optional<Set<String>>> nodeServices() {
            return CompletableFuture.completedFuture(listed);
        }

        @Override public void close() { }

        private static CatalogOutcome denied() {
            return new CatalogOutcome.Rejected("permission_denied", "Permission denied: token with AccessorID 'x' "
                    + "lacks permission 'service:write'", null);
        }
    }
}
