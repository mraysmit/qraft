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
import dev.mars.qraft.common.ServiceDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The one source of what a client wants registered: the configuration file's definitions and the local API's, by
 * the rules of Consul's agent:
 * <ul>
 *   <li>at run time, the latest registration or deregistration of an ID wins, including for a service the file
 *       defines;</li>
 *   <li>at startup, the file's definitions are registered again and take precedence over a kept registration of the
 *       same ID, which is purged, as it is by Consul's {@code loadServices} and {@code loadChecks};</li>
 *   <li>a kept check whose service is gone is purged, as Consul purges a check it cannot restore;</li>
 *   <li>a check ID is unique on a client, whichever service the check is for.</li>
 * </ul>
 *
 * <p>Only what the API registered is kept: the file's definitions are read again at every start. A change is kept
 * before it takes effect, so a change that cannot be kept is refused with nothing changed.
 *
 * <p>Reads take no lock. The reconciler reads this under its own lock, and a change tells the reconciler under this
 * one, so a lock here would invert the order.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-31
 * @version 1.0
 */
public final class LocalDefinitions {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalDefinitions.class);

    /** Told of each change, in order, once it has been kept. */
    public interface Listener {
        /** A check that must now run. A check whose definition changed is removed, then added. */
        void checkAdded(HealthCheckDefinition check);

        /** A check that must no longer run. */
        void checkRemoved(HealthCheckDefinition check);

        /** What is wanted of the servers changed: a service, or the checks a service declares. */
        void changed();
    }

    /** An immutable view of the definitions; each change replaces it whole. */
    private record State(Map<String, ServiceDefinition> services, Map<String, HealthCheckDefinition> checks,
                         Set<String> keptServices, Set<String> keptChecks, List<ServiceDefinition> declared) {
        static State of(Map<String, ServiceDefinition> services, Map<String, HealthCheckDefinition> checks,
                        Set<String> keptServices, Set<String> keptChecks) {
            List<ServiceDefinition> declared = services.values().stream()
                    .map(service -> service.withCheckIds(checks.values().stream()
                            .filter(check -> check.serviceId().equals(service.id()))
                            .map(HealthCheckDefinition::checkId).toList()))
                    .toList();
            return new State(services, checks, keptServices, keptChecks, declared);
        }

        RegistrationStore.Kept kept() {
            return new RegistrationStore.Kept(
                    services.values().stream().filter(service -> keptServices.contains(service.id())).toList(),
                    checks.values().stream().filter(check -> keptChecks.contains(check.checkId())).toList());
        }

        Optional<ServiceDefinition> enabledService(String serviceId) {
            return declared.stream().filter(service -> service.enabled() && service.id().equals(serviceId))
                    .findFirst();
        }

        Change change() {
            return new Change(new LinkedHashMap<>(services), new LinkedHashMap<>(checks),
                    new LinkedHashSet<>(keptServices), new LinkedHashSet<>(keptChecks));
        }
    }

    /** A change being made, on copies of the current state. */
    private record Change(Map<String, ServiceDefinition> services, Map<String, HealthCheckDefinition> checks,
                          Set<String> keptServices, Set<String> keptChecks) {
        void removeChecksOf(String serviceId) {
            checks.values().removeIf(check -> check.serviceId().equals(serviceId));
            keptChecks.retainAll(checks.keySet());
        }

        void addKeptCheck(HealthCheckDefinition check) {
            checks.put(check.checkId(), check);
            keptChecks.add(check.checkId());
        }
    }

    private final RegistrationStore store;
    private volatile State state;
    private Listener listener = new Listener() {
        @Override public void checkAdded(HealthCheckDefinition check) { }
        @Override public void checkRemoved(HealthCheckDefinition check) { }
        @Override public void changed() { }
    };

    private LocalDefinitions(RegistrationStore store, State state) {
        this.store = store;
        this.state = state;
    }

    /**
     * Starts from the file's definitions and restores what was kept. A check of a disabled service is not taken: its
     * service is not registered, so it never runs.
     *
     * @throws IllegalStateException if what was kept cannot be read
     * @throws IOException           if what was purged cannot be removed from what is kept
     */
    public static LocalDefinitions load(List<ServiceDefinition> fileServices, List<HealthCheckDefinition> fileChecks,
                                        RegistrationStore store) throws IOException {
        Map<String, ServiceDefinition> services = new LinkedHashMap<>();
        fileServices.forEach(service -> services.put(service.id(), service.withCheckIds(List.of())));
        Map<String, HealthCheckDefinition> checks = new LinkedHashMap<>();
        for (HealthCheckDefinition check : fileChecks) {
            if (isEnabled(services, check.serviceId())) checks.put(check.checkId(), check);
        }
        RegistrationStore.Kept kept = store.read();
        Set<String> keptServices = new LinkedHashSet<>();
        for (ServiceDefinition service : kept.services()) {
            if (services.putIfAbsent(service.id(), service) == null) keptServices.add(service.id());
        }
        Set<String> keptChecks = new LinkedHashSet<>();
        for (HealthCheckDefinition check : kept.checks()) {
            if (checks.containsKey(check.checkId())) continue;
            if (!isEnabled(services, check.serviceId())) {
                LOGGER.warn("Failed to restore check {}: its service {} is not registered, so it is no longer kept",
                        check.checkId(), check.serviceId());
                continue;
            }
            checks.put(check.checkId(), check);
            keptChecks.add(check.checkId());
        }
        State loaded = State.of(services, checks, keptServices, keptChecks);
        if (!loaded.kept().equals(kept)) store.write(loaded.kept());
        return new LocalDefinitions(store, loaded);
    }

    private static boolean isEnabled(Map<String, ServiceDefinition> services, String serviceId) {
        ServiceDefinition service = services.get(serviceId);
        return service != null && service.enabled();
    }

    /** Sets who is told of changes from now on. */
    public synchronized void listen(Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /** Every definition, declaring its checks, in the form the reconciler reads: a disabled one is not registered. */
    public List<ServiceDefinition> services() {
        return state.declared();
    }

    /** Every check that should be running. */
    public List<HealthCheckDefinition> checks() {
        return List.copyOf(state.checks().values());
    }

    /** A service that is registered, declaring its checks. A disabled definition is not. */
    public Optional<ServiceDefinition> service(String serviceId) {
        return state.enabledService(serviceId);
    }

    /**
     * Registers a service and the checks it carries. The service's other checks stay unless
     * {@code replaceExistingChecks} asks otherwise, as with Consul's {@code replace-existing-checks}.
     *
     * @throws IOException if the change cannot be kept; nothing has changed
     */
    public synchronized void register(Registration registration, boolean replaceExistingChecks) throws IOException {
        ServiceDefinition service = registration.service().withCheckIds(List.of());
        Change change = state.change();
        change.services().put(service.id(), service);
        change.keptServices().add(service.id());
        if (replaceExistingChecks) change.removeChecksOf(service.id());
        registration.checks().forEach(change::addKeptCheck);
        commit(change);
    }

    /**
     * Removes a service and its checks.
     *
     * @return whether it was registered: removing one that is not changes nothing and is not a failure
     * @throws IOException if the change cannot be kept; nothing has changed
     */
    public synchronized boolean deregisterService(String serviceId) throws IOException {
        if (state.enabledService(serviceId).isEmpty()) return false;
        Change change = state.change();
        change.services().remove(serviceId);
        change.keptServices().remove(serviceId);
        change.removeChecksOf(serviceId);
        commit(change);
        return true;
    }

    /**
     * Registers a check for its service, replacing any check with its ID on this client.
     *
     * @return whether the check's service is registered; if not, nothing has changed
     * @throws IOException if the change cannot be kept; nothing has changed
     */
    public synchronized boolean registerCheck(HealthCheckDefinition check) throws IOException {
        if (state.enabledService(check.serviceId()).isEmpty()) return false;
        Change change = state.change();
        change.addKeptCheck(check);
        commit(change);
        return true;
    }

    /**
     * Removes a check.
     *
     * @return whether it was registered: removing one that is not changes nothing and is not a failure
     * @throws IOException if the change cannot be kept; nothing has changed
     */
    public synchronized boolean deregisterCheck(String checkId) throws IOException {
        if (!state.checks().containsKey(checkId)) return false;
        Change change = state.change();
        change.checks().remove(checkId);
        change.keptChecks().remove(checkId);
        commit(change);
        return true;
    }

    private void commit(Change change) throws IOException {
        State previous = state;
        State next = State.of(change.services(), change.checks(), change.keptServices(), change.keptChecks());
        if (!next.kept().equals(previous.kept())) store.write(next.kept());
        state = next;
        previous.checks().forEach((id, check) -> {
            if (!check.equals(next.checks().get(id))) listener.checkRemoved(check);
        });
        next.checks().forEach((id, check) -> {
            if (!check.equals(previous.checks().get(id))) listener.checkAdded(check);
        });
        if (!next.declared().equals(previous.declared())) listener.changed();
    }
}
