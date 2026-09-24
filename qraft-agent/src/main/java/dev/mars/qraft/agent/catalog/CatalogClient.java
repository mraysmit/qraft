package dev.mars.qraft.agent.catalog;

import dev.mars.qraft.catalog.ServiceDefinition;

import java.util.concurrent.CompletableFuture;

/** Outbound port for node-scoped catalog registration. */
public interface CatalogClient extends AutoCloseable {
    CompletableFuture<CatalogOutcome> register(ServiceDefinition service);
    CompletableFuture<CatalogOutcome> deregister(String serviceId);
    CompletableFuture<CatalogLookupOutcome> lookup(ServiceDefinition service);
    @Override void close();
}
