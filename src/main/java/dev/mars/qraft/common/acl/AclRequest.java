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

package dev.mars.qraft.common.acl;

import java.util.Objects;

/**
 * What a caller asks to do: {@code read} or {@code write} a resource, by name for {@code node}, {@code service} and
 * {@code client}, and for a service in a tenant and a namespace. {@code operator} and {@code acl} are asked for
 * without a name.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-05
 * @version 1.0
 */
public record AclRequest(AclResource resource, String name, String tenant, String namespace, AclAccess access) {
    public AclRequest {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(access, "access");
        if (access == AclAccess.DENY) throw new IllegalArgumentException("a request asks to read or to write");
        if (resource.named() != (name != null)) {
            throw new IllegalArgumentException(resource.named() ? "a " + resource.ruleName() + " request names it"
                    : "a " + resource.ruleName() + " request names nothing: it is a single value");
        }
        if (resource.scoped()) {
            requireScope(tenant, "tenant");
            requireScope(namespace, "namespace");
        } else if (tenant != null || namespace != null) {
            throw new IllegalArgumentException("a " + resource.ruleName() + " request has no tenant or namespace");
        }
    }

    /** A request for {@code operator} or {@code acl}. */
    public static AclRequest single(AclResource resource, AclAccess access) {
        return new AclRequest(resource, null, null, null, access);
    }

    /** A request for the {@code node} or {@code client} named {@code name}. */
    public static AclRequest named(AclResource resource, String name, AclAccess access) {
        return new AclRequest(resource, Objects.requireNonNull(name, "name"), null, null, access);
    }

    /** A request for the service named {@code name} in {@code tenant} and {@code namespace}. */
    public static AclRequest service(String name, String tenant, String namespace, AclAccess access) {
        return new AclRequest(AclResource.SERVICE, Objects.requireNonNull(name, "name"), tenant, namespace, access);
    }

    private static void requireScope(String value, String what) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("a service request needs a " + what);
    }
}
