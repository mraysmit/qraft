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

import java.util.Comparator;

/**
 * A service identity: {@code write} on one service name in its tenant and namespace, and {@code read} on every
 * service and every node, as in Consul (ACL list, proposal 9). A token has no scope, so the identity carries the
 * tenant and namespace of its service, {@code default} unless given, where Consul Enterprise takes them from the
 * token's partition and namespace. There is no sidecar rule, because Qraft has no mesh.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-04
 * @version 1.0
 */
public record ServiceIdentity(String serviceName, String tenant, String namespace)
        implements Comparable<ServiceIdentity> {
    private static final Comparator<ServiceIdentity> ORDER = Comparator.comparing(ServiceIdentity::tenant)
            .thenComparing(ServiceIdentity::namespace).thenComparing(ServiceIdentity::serviceName);

    public ServiceIdentity {
        serviceName = AclNames.identityName(serviceName, "a service identity's service name");
        tenant = tenant == null ? AclScope.DEFAULT.name() : AclNames.identityName(tenant, "a service identity's tenant");
        namespace = namespace == null ? AclScope.DEFAULT.name()
                : AclNames.identityName(namespace, "a service identity's namespace");
    }

    @Override
    public int compareTo(ServiceIdentity other) {
        return ORDER.compare(this, other);
    }
}
