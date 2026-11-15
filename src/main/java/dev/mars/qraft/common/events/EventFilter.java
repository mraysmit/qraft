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

package dev.mars.qraft.common.events;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Which events a reader wants (architecture section 8): an empty set of categories or types takes every one, and a
 * tenant or namespace, when given, takes only the events in it, so an event without a scope matches no scope. A
 * service, when given, takes only the {@link ServiceEvent}s that name it (UI-4 list, decision 2).
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public record EventFilter(Set<EventCategory> categories, Set<String> types, Optional<String> tenant,
                          Optional<String> namespace, Optional<String> service) {
    public EventFilter {
        categories = Set.copyOf(Objects.requireNonNull(categories, "categories"));
        types = Set.copyOf(Objects.requireNonNull(types, "types"));
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(service, "service");
    }

    /** The events of the categories, types and scope given, of any service. */
    public EventFilter(Set<EventCategory> categories, Set<String> types, Optional<String> tenant,
                       Optional<String> namespace) {
        this(categories, types, tenant, namespace, Optional.empty());
    }

    /** Every event. */
    public static EventFilter all() {
        return new EventFilter(Set.of(), Set.of(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    public boolean matches(EventEnvelope envelope) {
        if (!categories.isEmpty() && !categories.contains(envelope.category())) return false;
        if (!types.isEmpty() && !types.contains(envelope.event().type())) return false;
        if (tenant.isPresent() && !tenant.equals(envelope.tenant())) return false;
        if (namespace.isPresent() && !namespace.equals(envelope.namespace())) return false;
        return service.isEmpty() || envelope.event() instanceof ServiceEvent named
                && service.get().equals(named.serviceName());
    }
}
