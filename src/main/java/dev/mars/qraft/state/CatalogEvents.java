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

package dev.mars.qraft.state;

import dev.mars.qraft.common.events.QraftEvent;

import java.util.Objects;

/**
 * The catalog's domain events (events list, Phase 4, decision 5): deterministic consequences of committed commands,
 * the same on every replica.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public final class CatalogEvents {
    private CatalogEvents() { }

    /** A node registered under a name that was free. */
    public record NodeRegistered(String name) implements QraftEvent {
        public NodeRegistered {
            Objects.requireNonNull(name, "name");
        }

        @Override
        public String type() {
            return "node.registered";
        }
    }

    /** A registered node registered again, with what it carries now. */
    public record NodeUpdated(String name) implements QraftEvent {
        public NodeUpdated {
            Objects.requireNonNull(name, "name");
        }

        @Override
        public String type() {
            return "node.updated";
        }
    }

    /** A node that stopped answering was marked unreachable. */
    public record NodeUnreachable(String name) implements QraftEvent {
        public NodeUnreachable {
            Objects.requireNonNull(name, "name");
        }

        @Override
        public String type() {
            return "node.unreachable";
        }
    }

    /** A node left the catalog: {@code deregistered}, {@code reaped} after being unreachable, or {@code replaced}. */
    public record NodeRemoved(String name, String reason) implements QraftEvent {
        public NodeRemoved {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public String type() {
            return "node.removed";
        }
    }

    /** A service instance was registered on a node. */
    public record ServiceRegistered(String serviceId, String serviceName, String node) implements QraftEvent {
        @Override
        public String type() {
            return "service.registered";
        }
    }

    /** A service instance left the catalog. */
    public record ServiceDeregistered(String serviceId, String serviceName, String node) implements QraftEvent {
        @Override
        public String type() {
            return "service.deregistered";
        }
    }

    /** A service instance's aggregate health changed, between {@code ServiceHealth} names. */
    public record HealthChanged(String serviceId, String serviceName, String from, String to) implements QraftEvent {
        @Override
        public String type() {
            return "health.changed";
        }
    }
}
