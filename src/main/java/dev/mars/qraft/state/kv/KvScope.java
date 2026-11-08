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

package dev.mars.qraft.state.kv;

import java.util.Objects;

/**
 * The tenant and namespace a key belongs to (key/value list, decision 3).
 *
 * @param tenant    the key's tenant
 * @param namespace the key's namespace within it
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public record KvScope(String tenant, String namespace) {
    /** The scope of a key whose request names none. */
    public static final KvScope DEFAULT = new KvScope("default", "default");

    public KvScope {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(namespace, "namespace");
        if (tenant.isBlank() || namespace.isBlank()) {
            throw new IllegalArgumentException("a key's tenant and namespace must not be blank");
        }
    }
}
