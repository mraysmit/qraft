package dev.mars.qraft.tenant.service;

import dev.mars.qraft.tenant.model.Namespace;

import java.util.List;
import java.util.Optional;

/** Lifecycle API for Consul-style namespaces. */
public interface NamespaceService {
    Namespace create(Namespace namespace) throws NamespaceException;
    Namespace update(Namespace namespace) throws NamespaceException;
    Optional<Namespace> find(String name);
    List<Namespace> list();
    void delete(String name) throws NamespaceException;

    class NamespaceException extends Exception {
        public NamespaceException(String message) { super(message); }
    }
}
