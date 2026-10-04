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

package dev.mars.qraft.agent.catalog;

import dev.mars.qraft.catalog.ServiceDefinition;

import java.util.concurrent.CompletableFuture;

/**
 * Outbound port for node-scoped catalog registration.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-24
 * @version 1.0
 */
public interface CatalogClient extends AutoCloseable {
    CompletableFuture<CatalogOutcome> register(ServiceDefinition service);
    CompletableFuture<CatalogOutcome> deregister(String serviceId);
    CompletableFuture<CatalogLookupOutcome> lookup(ServiceDefinition service);
    @Override void close();
}
