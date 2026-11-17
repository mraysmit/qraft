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

package dev.mars.qraft.state.tenancy;

import dev.mars.qraft.common.events.QraftEvent;

/**
 * The tenancy's domain events (UI-6 Tenants, Namespaces and Audit task list, decision 1): a tenant's are published in
 * its own tenant and its {@code default} namespace, and a namespace's in its own scope, so a scope's recent events hold
 * its history. Each names its scope, and none carries a description.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-17
 * @version 1.0
 */
public final class TenancyEvents {
    private TenancyEvents() { }

    public record TenantCreated(String name) implements QraftEvent {
        @Override
        public String type() {
            return "tenant.created";
        }
    }

    public record TenantUpdated(String name) implements QraftEvent {
        @Override
        public String type() {
            return "tenant.updated";
        }
    }

    public record TenantDeleted(String name) implements QraftEvent {
        @Override
        public String type() {
            return "tenant.deleted";
        }
    }

    public record NamespaceCreated(String tenant, String name) implements QraftEvent {
        @Override
        public String type() {
            return "namespace.created";
        }
    }

    public record NamespaceUpdated(String tenant, String name) implements QraftEvent {
        @Override
        public String type() {
            return "namespace.updated";
        }
    }

    public record NamespaceDeleted(String tenant, String name) implements QraftEvent {
        @Override
        public String type() {
            return "namespace.deleted";
        }
    }
}
