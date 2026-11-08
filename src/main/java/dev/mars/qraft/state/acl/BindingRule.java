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

package dev.mars.qraft.state.acl;

import java.util.Objects;

/**
 * A binding rule (authentication list, Phase 1), as Consul's: a login through {@code authMethod} whose mapped claims
 * {@code selector} matches is bound to the {@code bindType} named {@code bindName}, which may interpolate the mapped
 * claims as {@code ${value.name}}.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public record BindingRule(String id, String description, String authMethod, String selector, BindType bindType,
                          String bindName, long createIndex, long modifyIndex) {
    public BindingRule {
        Objects.requireNonNull(id, "id");
        description = description == null ? "" : description;
        Objects.requireNonNull(authMethod, "authMethod");
        selector = selector == null ? "" : selector;
        Objects.requireNonNull(bindType, "bindType");
        Objects.requireNonNull(bindName, "bindName");
    }

    BindingRule at(long create, long modify) {
        return new BindingRule(id, description, authMethod, selector, bindType, bindName, create, modify);
    }
}
