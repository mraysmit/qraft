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

package dev.mars.qraft.client.service;

import dev.mars.qraft.client.config.DefinitionJson.Registration;
import dev.mars.qraft.client.health.HealthCheckDefinition;
import dev.mars.qraft.client.health.MaintenanceCheck;
import dev.mars.qraft.client.health.TtlCheck;
import dev.mars.qraft.common.ServiceDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link LocalDefinitions}, the one source of what a client wants registered, by the client list's decision 7:
 * at run time the latest registration or deregistration of an ID wins, including for a service the configuration file
 * defines, and at startup the file's definitions are registered again and take precedence over what was kept. The
 * rules are Consul's, read in {@code agent/agent.go} on 2026-10-31.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-31
 * @version 1.0
 */
class LocalDefinitionsTest {
    private static final ServiceDefinition FILE_WEB = service("web", 8080);
    private static final HealthCheckDefinition FILE_APP = check("web", "app", 30);

    @TempDir
    Path directory;
    private RegistrationStore store;
    private final List<String> events = new ArrayList<>();
    private final LocalDefinitions.Listener listener = new LocalDefinitions.Listener() {
        @Override public void checkAdded(HealthCheckDefinition check) {
            events.add("added " + check.serviceId() + "/" + check.checkId());
        }
        @Override public void checkRemoved(HealthCheckDefinition check) {
            events.add("removed " + check.serviceId() + "/" + check.checkId());
        }
        @Override public void changed() { events.add("changed"); }
    };

    @BeforeEach
    void openStore() {
        store = new RegistrationStore(directory);
    }

    private static ServiceDefinition service(String id, int port) {
        return new ServiceDefinition(id, id, "10.0.0.5", port, List.of(), Map.of(), true);
    }

    private static HealthCheckDefinition check(String serviceId, String checkId, int ttlSeconds) {
        return new TtlCheck(serviceId, checkId, Duration.ofSeconds(ttlSeconds), true);
    }

    private LocalDefinitions open(List<ServiceDefinition> fileServices, List<HealthCheckDefinition> fileChecks)
            throws IOException {
        LocalDefinitions definitions = LocalDefinitions.load(fileServices, fileChecks, store);
        definitions.listen(listener);
        return definitions;
    }

    private static List<String> ids(List<ServiceDefinition> services) {
        return services.stream().map(ServiceDefinition::id).toList();
    }

    private static ServiceDefinition named(LocalDefinitions definitions, String id) {
        return definitions.services().stream().filter(service -> service.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void theFilesDefinitionsAreTheStartAndDeclareTheirChecks() throws Exception {
        LocalDefinitions definitions = open(List.of(FILE_WEB), List.of(FILE_APP));

        assertEquals(List.of("web"), ids(definitions.services()));
        assertEquals(List.of("app"), named(definitions, "web").checkIds());
        assertEquals(List.of(FILE_APP), definitions.checks());
        assertFalse(Files.exists(directory.resolve(RegistrationStore.FILE_NAME)),
                "nothing the file defines is kept: it is registered again at every start");
    }

    @Test
    void aDisabledFileServiceStaysInTheSourceButIsNotRegisteredAndItsChecksDoNotRun() throws Exception {
        ServiceDefinition disabled = new ServiceDefinition("legacy", "legacy", "10.0.0.5", 8081, List.of(),
                Map.of(), false);
        LocalDefinitions definitions = open(List.of(FILE_WEB, disabled),
                List.of(FILE_APP, check("legacy", "legacy-app", 30)));

        assertEquals(List.of("web", "legacy"), ids(definitions.services()));
        assertEquals(List.of(FILE_APP), definitions.checks());
        assertTrue(definitions.service("legacy").isEmpty());
        assertFalse(definitions.deregisterService("legacy"), "it is not registered, so there is nothing to remove");
    }

    @Test
    void aRegistrationAddsTheServiceDeclaresItsChecksRunsThemAndAsksForAReconciliation() throws Exception {
        LocalDefinitions definitions = open(List.of(FILE_WEB), List.of());
        HealthCheckDefinition check = check("api", "ttl", 30);

        definitions.register(new Registration(service("api", 9000), List.of(check)), false);

        assertEquals(List.of("web", "api"), ids(definitions.services()));
        assertEquals(List.of("ttl"), named(definitions, "api").checkIds());
        assertEquals(service("api", 9000).withCheckIds(List.of("ttl")), definitions.service("api").orElseThrow());
        assertEquals(List.of(check), definitions.checks());
        assertEquals(List.of("added api/ttl", "changed"), events);
    }

    @Test
    void registeringTheSameAgainDisturbsNothingAsAnApplicationMayRepeatItsRegistration() throws Exception {
        LocalDefinitions definitions = open(List.of(), List.of());
        Registration registration = new Registration(service("api", 9000), List.of(check("api", "ttl", 30)));
        definitions.register(registration, false);
        events.clear();
        long kept = Files.getLastModifiedTime(directory.resolve(RegistrationStore.FILE_NAME)).toMillis();

        definitions.register(registration, false);

        assertEquals(List.of(), events);
        assertEquals(kept, Files.getLastModifiedTime(directory.resolve(RegistrationStore.FILE_NAME)).toMillis());
    }

    @Test
    void aRegistrationReplacesAFileServiceAndTheLatestWinsAtRunTime() throws Exception {
        LocalDefinitions definitions = open(List.of(FILE_WEB), List.of());

        definitions.register(new Registration(service("web", 9999), List.of()), false);

        assertEquals(9999, named(definitions, "web").port());
        assertEquals(List.of("web"), ids(definitions.services()));
        assertEquals(List.of("changed"), events);
    }

    @Test
    void aRegistrationKeepsTheServicesOtherChecksAsConsulsDoes() throws Exception {
        LocalDefinitions definitions = open(List.of(FILE_WEB), List.of(FILE_APP));
        HealthCheckDefinition added = check("web", "second", 30);

        definitions.register(new Registration(FILE_WEB, List.of(added)), false);

        assertEquals(List.of("app", "second"), named(definitions, "web").checkIds());
        assertEquals(List.of("added web/second", "changed"), events);
    }

    @Test
    void replacingExistingChecksRemovesThoseTheRegistrationDoesNotCarry() throws Exception {
        LocalDefinitions definitions = open(List.of(FILE_WEB), List.of(FILE_APP));
        HealthCheckDefinition added = check("web", "second", 30);

        definitions.register(new Registration(FILE_WEB, List.of(added)), true);

        assertEquals(List.of("second"), named(definitions, "web").checkIds());
        assertEquals(List.of(added), definitions.checks());
        assertEquals(List.of("removed web/app", "added web/second", "changed"), events);
    }

    @Test
    void aChangedCheckIsRemovedAndAddedSoThatItStartsAfresh() throws Exception {
        LocalDefinitions definitions = open(List.of(FILE_WEB), List.of(FILE_APP));

        definitions.registerCheck(check("web", "app", 60));

        assertEquals(List.of(check("web", "app", 60)), definitions.checks());
        assertEquals(List.of("removed web/app", "added web/app"), events,
                "the service's declared checks are the same, so nothing needs reconciling");
    }

    @Test
    void aCheckIdIsUniqueOnAClientSoTheLatestRegistrationMovesIt() throws Exception {
        LocalDefinitions definitions = open(List.of(FILE_WEB, service("api", 9000)), List.of(FILE_APP));

        assertTrue(definitions.registerCheck(check("api", "app", 30)));

        assertEquals(List.of(), named(definitions, "web").checkIds());
        assertEquals(List.of("app"), named(definitions, "api").checkIds());
        assertEquals(List.of("removed web/app", "added api/app", "changed"), events);
    }

    @Test
    void aCheckForAServiceThatIsNotRegisteredIsRefused() throws Exception {
        LocalDefinitions definitions = open(List.of(FILE_WEB), List.of());

        assertFalse(definitions.registerCheck(check("nobody", "ttl", 30)));

        assertEquals(List.of(), definitions.checks());
        assertEquals(List.of(), events);
    }

    @Test
    void deregisteringAServiceRemovesItsChecksAndAKnownAnswerSaysSo() throws Exception {
        LocalDefinitions definitions = open(List.of(FILE_WEB), List.of(FILE_APP));

        assertTrue(definitions.deregisterService("web"));

        assertEquals(List.of(), definitions.services());
        assertEquals(List.of(), definitions.checks());
        assertEquals(List.of("removed web/app", "changed"), events);
        assertFalse(definitions.deregisterService("web"), "deregistering is idempotent, as Consul's");
        assertEquals(List.of("removed web/app", "changed"), events);
    }

    @Test
    void deregisteringACheckStopsItAndTheServiceNoLongerDeclaresIt() throws Exception {
        LocalDefinitions definitions = open(List.of(FILE_WEB), List.of(FILE_APP));

        assertTrue(definitions.deregisterCheck("app"));

        assertEquals(List.of(), named(definitions, "web").checkIds());
        assertEquals(List.of("removed web/app", "changed"), events);
        assertFalse(definitions.deregisterCheck("app"));
    }

    @Test
    void whatTheApiRegisteredIsRestoredAtTheNextStart() throws Exception {
        LocalDefinitions first = open(List.of(FILE_WEB), List.of());
        HealthCheckDefinition onApi = check("api", "ttl", 30);
        HealthCheckDefinition onFileService = check("web", "second", 30);
        first.register(new Registration(service("api", 9000), List.of(onApi)), false);
        first.registerCheck(onFileService);

        LocalDefinitions restarted = LocalDefinitions.load(List.of(FILE_WEB), List.of(), new RegistrationStore(directory));

        assertEquals(List.of("web", "api"), ids(restarted.services()));
        assertEquals(List.of("second"), named(restarted, "web").checkIds());
        assertEquals(List.of("ttl"), named(restarted, "api").checkIds());
    }

    @Test
    void aDeregistrationIsKeptToo() throws Exception {
        LocalDefinitions first = open(List.of(), List.of());
        first.register(new Registration(service("api", 9000), List.of(check("api", "ttl", 30))), false);
        first.register(new Registration(service("other", 9001), List.of()), false);

        first.deregisterService("api");

        LocalDefinitions restarted = LocalDefinitions.load(List.of(), List.of(), new RegistrationStore(directory));
        assertEquals(List.of("other"), ids(restarted.services()));
        assertEquals(List.of(), restarted.checks());
    }

    @Test
    void aFileServiceDeregisteredAtRunTimeIsRegisteredAgainAtTheNextStart() throws Exception {
        LocalDefinitions first = open(List.of(FILE_WEB), List.of(FILE_APP));
        first.deregisterService("web");

        LocalDefinitions restarted = LocalDefinitions.load(List.of(FILE_WEB), List.of(FILE_APP),
                new RegistrationStore(directory));

        assertEquals(List.of("web"), ids(restarted.services()));
        assertEquals(List.of(FILE_APP), restarted.checks());
    }

    @Test
    void atStartupTheFileTakesPrecedenceOverWhatWasKeptAndTheKeptCopyIsPurgedAsConsulsIs() throws Exception {
        LocalDefinitions first = open(List.of(FILE_WEB), List.of(FILE_APP));
        first.register(new Registration(service("web", 9999), List.of(check("web", "app", 60))), false);

        LocalDefinitions restarted = LocalDefinitions.load(List.of(FILE_WEB), List.of(FILE_APP),
                new RegistrationStore(directory));

        assertEquals(8080, named(restarted, "web").port());
        assertEquals(List.of(FILE_APP), restarted.checks());
        assertEquals(new RegistrationStore.Kept(List.of(), List.of()), new RegistrationStore(directory).read(),
                "a kept copy the file overrode must not return when the file later stops defining it");
    }

    @Test
    void aKeptServiceTheFileNowDisablesStaysDisabled() throws Exception {
        LocalDefinitions first = open(List.of(), List.of());
        first.register(new Registration(service("legacy", 9000), List.of()), false);
        ServiceDefinition disabled = new ServiceDefinition("legacy", "legacy", "10.0.0.5", 9000, List.of(), Map.of(),
                false);

        LocalDefinitions restarted = LocalDefinitions.load(List.of(disabled), List.of(),
                new RegistrationStore(directory));

        assertTrue(restarted.service("legacy").isEmpty());
    }

    @Test
    void aKeptCheckWhoseServiceIsGoneIsPurgedAsConsulPurgesACheckItCannotRestore() throws Exception {
        LocalDefinitions first = open(List.of(FILE_WEB), List.of());
        first.registerCheck(check("web", "ttl", 30));

        LocalDefinitions restarted = LocalDefinitions.load(List.of(), List.of(), new RegistrationStore(directory));

        assertEquals(List.of(), restarted.checks());
        assertEquals(new RegistrationStore.Kept(List.of(), List.of()), new RegistrationStore(directory).read());
    }

    @Test
    void aRegistrationThatCannotBeKeptIsRefusedWithNothingChanged() throws Exception {
        LocalDefinitions definitions = open(List.of(FILE_WEB), List.of(FILE_APP));
        Path temporary = Files.createDirectory(directory.resolve(RegistrationStore.FILE_NAME + ".tmp"));

        assertThrows(IOException.class, () -> definitions.register(
                new Registration(service("api", 9000), List.of(check("api", "ttl", 30))), false));

        assertEquals(List.of("web"), ids(definitions.services()));
        assertEquals(List.of(FILE_APP), definitions.checks());
        assertEquals(List.of(), events, "nothing runs or is reconciled for what could not be kept");

        Files.delete(temporary);
        definitions.register(new Registration(service("api", 9000), List.of()), false);
        assertEquals(List.of("web", "api"), ids(definitions.services()));
    }

    @Test
    void aServiceInMaintenanceHasACheckThatItDeclaresAndThatGoesWhenMaintenanceEnds() throws Exception {
        LocalDefinitions definitions = open(List.of(FILE_WEB), List.of(FILE_APP));
        MaintenanceCheck maintenance = MaintenanceCheck.ofService("web", "upgrade");

        assertTrue(definitions.setServiceMaintenance("web", true, "upgrade"));

        assertEquals(List.of(maintenance.checkId(), "app"), named(definitions, "web").checkIds());
        assertEquals(List.of(FILE_APP, maintenance), definitions.checks());
        assertEquals(java.util.Optional.of(maintenance), definitions.check(maintenance.checkId()));
        assertEquals(List.of("added web/" + maintenance.checkId(), "changed"), events);

        events.clear();
        assertTrue(definitions.setServiceMaintenance("web", false, null));
        assertEquals(List.of("app"), named(definitions, "web").checkIds());
        assertEquals(List.of("removed web/" + maintenance.checkId(), "changed"), events);
    }

    @Test
    void enablingMaintenanceAgainKeepsTheFirstReasonAsConsulsDoes() throws Exception {
        LocalDefinitions definitions = open(List.of(FILE_WEB), List.of());
        definitions.setServiceMaintenance("web", true, "upgrade");
        definitions.setNodeMaintenance(true, "rack move");
        events.clear();

        assertTrue(definitions.setServiceMaintenance("web", true, "another"));
        definitions.setNodeMaintenance(true, "another");

        assertEquals(List.of(), events);
        assertTrue(definitions.checks().contains(MaintenanceCheck.ofService("web", "upgrade")));
        assertTrue(definitions.checks().contains(MaintenanceCheck.ofNode("web", "rack move")));
    }

    @Test
    void maintenanceOfAServiceThatIsNotRegisteredIsRefusedAndDisablingWhatIsNotEnabledChangesNothing() throws Exception {
        LocalDefinitions definitions = open(List.of(FILE_WEB), List.of());

        assertFalse(definitions.setServiceMaintenance("nobody", true, "upgrade"));
        assertFalse(definitions.setServiceMaintenance("nobody", false, null));
        assertTrue(definitions.setServiceMaintenance("web", false, null));
        definitions.setNodeMaintenance(false, null);

        assertEquals(List.of(), events);
    }

    @Test
    void aNodeInMaintenanceHasACheckOnEveryRegisteredServiceIncludingThoseThatComeLater() throws Exception {
        ServiceDefinition disabled = new ServiceDefinition("legacy", "legacy", "10.0.0.5", 8081, List.of(),
                Map.of(), false);
        LocalDefinitions definitions = open(List.of(FILE_WEB, disabled), List.of());

        definitions.setNodeMaintenance(true, "rack move");

        assertEquals(List.of(MaintenanceCheck.ofNode("web", "rack move")), definitions.checks());
        definitions.register(new Registration(service("api", 9000), List.of()), false);
        assertTrue(definitions.checks().contains(MaintenanceCheck.ofNode("api", "rack move")));
        assertEquals(List.of(MaintenanceCheck.ofNode("api", "rack move").checkId()),
                named(definitions, "api").checkIds());

        definitions.setNodeMaintenance(false, null);
        assertEquals(List.of(), definitions.checks());
    }

    @Test
    void deregisteringAServiceEndsItsMaintenanceSoThatItIsNotInMaintenanceWhenItComesBack() throws Exception {
        LocalDefinitions definitions = open(List.of(FILE_WEB), List.of());
        definitions.setServiceMaintenance("web", true, "upgrade");

        definitions.deregisterService("web");
        definitions.register(new Registration(FILE_WEB, List.of()), false);

        assertEquals(List.of(), definitions.checks());
    }

    @Test
    void maintenanceOfAServiceDoesNotStopItsOtherChecksOrChangeOnReRegistration() throws Exception {
        LocalDefinitions definitions = open(List.of(FILE_WEB), List.of());
        definitions.setServiceMaintenance("web", true, "upgrade");
        events.clear();

        definitions.register(new Registration(service("web", 9999), List.of()), true);

        assertEquals(List.of(MaintenanceCheck.ofService("web", "upgrade")), definitions.checks());
        assertEquals(List.of("changed"), events);
    }

    @Test
    void anApplicationCannotRemoveAMaintenanceCheckAsACheck() throws Exception {
        LocalDefinitions definitions = open(List.of(FILE_WEB), List.of());
        definitions.setServiceMaintenance("web", true, "upgrade");

        assertFalse(definitions.deregisterCheck(MaintenanceCheck.ofService("web", "upgrade").checkId()));

        assertEquals(List.of(MaintenanceCheck.ofService("web", "upgrade")), definitions.checks());
    }

    @Test
    void maintenancePersistsAcrossARestart() throws Exception {
        LocalDefinitions first = open(List.of(FILE_WEB), List.of());
        first.setServiceMaintenance("web", true, "upgrade");
        first.setNodeMaintenance(true, "rack move");

        LocalDefinitions restarted = LocalDefinitions.load(List.of(FILE_WEB), List.of(),
                new RegistrationStore(directory));

        assertEquals(List.of(MaintenanceCheck.ofService("web", "upgrade"), MaintenanceCheck.ofNode("web", "rack move")),
                restarted.checks());
    }

    @Test
    void keptMaintenanceOfAServiceThatIsGoneIsPurgedAtStartup() throws Exception {
        LocalDefinitions first = open(List.of(FILE_WEB), List.of());
        first.setServiceMaintenance("web", true, "upgrade");
        first.setNodeMaintenance(true, "rack move");

        LocalDefinitions restarted = LocalDefinitions.load(List.of(), List.of(), new RegistrationStore(directory));

        assertEquals(List.of(), restarted.checks());
        assertEquals(new RegistrationStore.Kept(List.of(), List.of(), "rack move", Map.of()),
                new RegistrationStore(directory).read(), "the node is still in maintenance");
    }

    @Test
    void maintenanceThatCannotBeKeptIsRefusedWithNothingChanged() throws Exception {
        LocalDefinitions definitions = open(List.of(FILE_WEB), List.of());
        Files.createDirectory(directory.resolve(RegistrationStore.FILE_NAME + ".tmp"));

        assertThrows(IOException.class, () -> definitions.setServiceMaintenance("web", true, "upgrade"));
        assertThrows(IOException.class, () -> definitions.setNodeMaintenance(true, "rack move"));

        assertEquals(List.of(), definitions.checks());
        assertEquals(List.of(), events);
    }

    @Test
    void aDeregistrationOfAFileServiceThatWasReRegisteredIsKeptAsWell() throws Exception {
        LocalDefinitions first = open(List.of(FILE_WEB), List.of());
        first.register(new Registration(service("web", 9999), List.of()), false);
        first.deregisterService("web");

        assertEquals(new RegistrationStore.Kept(List.of(), List.of()), new RegistrationStore(directory).read());
    }
}
