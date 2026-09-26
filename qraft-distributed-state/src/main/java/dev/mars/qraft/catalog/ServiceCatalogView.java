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

package dev.mars.qraft.catalog;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only view of a {@link ServiceCatalog}. Replicated catalog state changes only through committed
 * commands applied by the state machine, so readers receive this view rather than the catalog itself.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-26
 * @version 1.0
 */
public interface ServiceCatalogView {
    Optional<ServiceInstance> find(ServiceInstanceId identity);

    List<String> services();

    List<ServiceInstance> instances(String serviceName);

    List<ServiceInstance> instances();

    /** Returns a view that delegates reads to {@code catalog} and cannot be cast back to it. */
    static ServiceCatalogView of(ServiceCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog");
        return new ServiceCatalogView() {
            @Override public Optional<ServiceInstance> find(ServiceInstanceId identity) { return catalog.find(identity); }
            @Override public List<String> services() { return catalog.services(); }
            @Override public List<ServiceInstance> instances(String serviceName) { return catalog.instances(serviceName); }
            @Override public List<ServiceInstance> instances() { return catalog.instances(); }
        };
    }
}
