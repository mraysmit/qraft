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

import dev.mars.qraft.raft.RaftCommand;

import java.util.Objects;

/**
 * A replicated change to the tenants and namespaces (tenancy list, Phase 1). The state machine refuses what cannot be
 * done, a name that is not one included, so every replica refuses the same commands.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public sealed interface TenancyCommand extends RaftCommand {
    record CreateTenant(String name, String description) implements TenancyCommand {
        private static final long serialVersionUID = 1L;

        public CreateTenant {
            Objects.requireNonNull(name, "name");
            description = description == null ? "" : description;
        }
    }

    record UpdateTenant(String name, String description) implements TenancyCommand {
        private static final long serialVersionUID = 1L;

        public UpdateTenant {
            Objects.requireNonNull(name, "name");
            description = description == null ? "" : description;
        }
    }

    record DeleteTenant(String name) implements TenancyCommand {
        private static final long serialVersionUID = 1L;

        public DeleteTenant {
            Objects.requireNonNull(name, "name");
        }
    }

    record CreateNamespace(String tenant, String name, String description) implements TenancyCommand {
        private static final long serialVersionUID = 1L;

        public CreateNamespace {
            Objects.requireNonNull(tenant, "tenant");
            Objects.requireNonNull(name, "name");
            description = description == null ? "" : description;
        }
    }

    record UpdateNamespace(String tenant, String name, String description) implements TenancyCommand {
        private static final long serialVersionUID = 1L;

        public UpdateNamespace {
            Objects.requireNonNull(tenant, "tenant");
            Objects.requireNonNull(name, "name");
            description = description == null ? "" : description;
        }
    }

    record DeleteNamespace(String tenant, String name) implements TenancyCommand {
        private static final long serialVersionUID = 1L;

        public DeleteNamespace {
            Objects.requireNonNull(tenant, "tenant");
            Objects.requireNonNull(name, "name");
        }
    }

    static TenancyCommand createTenant(String name, String description) {
        return new CreateTenant(name, description);
    }

    static TenancyCommand updateTenant(String name, String description) {
        return new UpdateTenant(name, description);
    }

    static TenancyCommand deleteTenant(String name) {
        return new DeleteTenant(name);
    }

    static TenancyCommand createNamespace(String tenant, String name, String description) {
        return new CreateNamespace(tenant, name, description);
    }

    static TenancyCommand updateNamespace(String tenant, String name, String description) {
        return new UpdateNamespace(tenant, name, description);
    }

    static TenancyCommand deleteNamespace(String tenant, String name) {
        return new DeleteNamespace(tenant, name);
    }
}
