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

package dev.mars.qraft.state.tenancy;

import java.util.Objects;

/**
 * A namespace in a tenant (tenancy list, decision 1), with the Raft indexes that created it and last changed it; the
 * {@code default} tenant's {@code default} namespace has index 0.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public record Namespace(String tenant, String name, String description, long createIndex, long modifyIndex) {
    public Namespace {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(name, "name");
        description = description == null ? "" : description;
    }
}
