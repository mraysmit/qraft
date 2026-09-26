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

import java.util.List;
import java.util.Optional;

/**
 * Lifecycle API for Consul-style namespaces.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-09
 * @version 1.0
 */
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
