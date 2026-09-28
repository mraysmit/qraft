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

package dev.mars.qraft.tenant.service;

import dev.mars.qraft.tenant.model.Namespace;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe in-memory namespace registry used by the pure Java runtime.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-09
 * @version 1.0
 */
public final class InMemoryNamespaceService implements NamespaceService {
    private final ConcurrentMap<String, Namespace> namespaces;

    public InMemoryNamespaceService() {
        this(new ConcurrentHashMap<>());
    }

    /** Uses {@code namespaces} as the store, so a test can interleave another operation deterministically. */
    InMemoryNamespaceService(ConcurrentMap<String, Namespace> namespaces) {
        this.namespaces = namespaces;
    }

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
        // One atomic step: a check followed by a write could restore a namespace deleted in between.
        if (namespaces.replace(namespace.name(), namespace) == null) {
            throw new NamespaceException("Namespace not found: " + namespace.name());
        }
        return namespace;
    }

    @Override
    public Optional<Namespace> find(String name) {
        return Optional.ofNullable(namespaces.get(requireName(name)));
    }

    @Override
    public List<Namespace> list() {
        return namespaces.values().stream()
                .sorted(Comparator.comparing(Namespace::name))
                .toList();
    }

    @Override
    public void delete(String name) throws NamespaceException {
        if (namespaces.remove(requireName(name)) == null) {
            throw new NamespaceException("Namespace not found: " + name);
        }
    }

    private static void require(Namespace namespace) {
        if (namespace == null) {
            throw new IllegalArgumentException("Namespace cannot be null");
        }
    }

    private static String requireName(String name) {
        if (name == null) {
            throw new IllegalArgumentException("Namespace name cannot be null");
        }
        return name;
    }
}
