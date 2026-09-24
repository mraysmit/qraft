package dev.mars.qraft.controller.state;

import dev.mars.qraft.catalog.ServiceInstance;
import dev.mars.qraft.catalog.ServiceInstanceId;

import java.util.Objects;

/** Mutations applied to the replicated service catalog. */
public sealed interface CatalogCommand extends RaftCommand
        permits CatalogCommand.Register, CatalogCommand.Deregister {

    record Register(ServiceInstance instance) implements CatalogCommand {
        public Register {
            Objects.requireNonNull(instance, "instance");
        }
    }

    record Deregister(String serviceId, String nodeId, String tenantId, String namespace)
            implements CatalogCommand {
        public Deregister {
            if (serviceId == null || serviceId.isBlank()) {
                throw new IllegalArgumentException("serviceId must not be blank");
            }
        }

        public boolean isLegacy() {
            return nodeId == null || nodeId.isBlank();
        }

        public ServiceInstanceId identity() {
            if (isLegacy()) throw new IllegalStateException("Legacy deregistration has no composite identity");
            return new ServiceInstanceId(tenantId, namespace, nodeId, serviceId);
        }
    }

    static CatalogCommand register(ServiceInstance instance) {
        return new Register(instance);
    }

    static CatalogCommand deregister(String serviceId) {
        return new Deregister(serviceId, "", "", "");
    }

    static CatalogCommand deregister(ServiceInstanceId identity) {
        Objects.requireNonNull(identity, "identity");
        return new Deregister(identity.serviceId(), identity.nodeId(),
                identity.tenantId(), identity.namespace());
    }
}
