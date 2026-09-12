package dev.mars.qraft.controller.state;

import dev.mars.qraft.catalog.ServiceInstance;

import java.util.Objects;

/** Mutations applied to the replicated service catalog. */
public sealed interface CatalogCommand extends RaftCommand
        permits CatalogCommand.Register, CatalogCommand.Deregister {

    record Register(ServiceInstance instance) implements CatalogCommand {
        public Register {
            Objects.requireNonNull(instance, "instance");
        }
    }

    record Deregister(String serviceId) implements CatalogCommand {
        public Deregister {
            if (serviceId == null || serviceId.isBlank()) {
                throw new IllegalArgumentException("serviceId must not be blank");
            }
        }
    }

    static CatalogCommand register(ServiceInstance instance) {
        return new Register(instance);
    }

    static CatalogCommand deregister(String serviceId) {
        return new Deregister(serviceId);
    }
}
