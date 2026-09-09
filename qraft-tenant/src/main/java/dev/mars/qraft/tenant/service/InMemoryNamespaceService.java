package dev.mars.qraft.tenant.service;

import dev.mars.qraft.tenant.model.Namespace;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe in-memory namespace registry used by the pure Java runtime. */
public final class InMemoryNamespaceService implements NamespaceService {
    private final ConcurrentHashMap<String, Namespace> namespaces = new ConcurrentHashMap<>();

    @Override
    public Namespace create(Namespace namespace) throws NamespaceException {
        require(namespace);
        Namespace existing = namespaces.putIfAbsent(namespace.name(), namespace);
        if (existing != null) {
            throw new NamespaceException("Namespace already exists: " + namespace.name());
        }
        return namespace;
    }

    @Override
    public Namespace update(Namespace namespace) throws NamespaceException {
        require(namespace);
        if (!namespaces.containsKey(namespace.name())) {
            throw new NamespaceException("Namespace not found: " + namespace.name());
        }
        namespaces.put(namespace.name(), namespace);
        return namespace;
    }

    @Override
    public Optional<Namespace> find(String name) {
        return Optional.ofNullable(namespaces.get(name));
    }

    @Override
    public List<Namespace> list() {
        return namespaces.values().stream()
                .sorted(Comparator.comparing(Namespace::name))
                .toList();
    }

    @Override
    public void delete(String name) throws NamespaceException {
        if (namespaces.remove(name) == null) {
            throw new NamespaceException("Namespace not found: " + name);
        }
    }

    private static void require(Namespace namespace) {
        if (namespace == null) {
            throw new IllegalArgumentException("Namespace cannot be null");
        }
    }
}
