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

import dev.mars.qraft.common.acl.NodeIdentity;
import dev.mars.qraft.common.acl.ServiceIdentity;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A role: a named set of policy links and identities, which a token that links to it holds (ACL list, proposal 8).
 * Its name is unique regardless of case and follows Consul's {@code validRoleName} in {@code acl/validation.go}:
 * 1 to 256 letters, digits, {@code -} and {@code _}. Its links and identities are sorted and distinct.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-04
 * @version 1.0
 */
public record AclRole(String id, String name, String description, List<String> policyIds,
                      List<NodeIdentity> nodeIdentities, List<ServiceIdentity> serviceIdentities) {
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9\\-_]{1,256}");

    public AclRole {
        id = AclSecrets.canonicalId(id, "a role's ID");
        if (name == null || !VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("not a valid role name: " + name);
        }
        description = description == null ? "" : description;
        policyIds = AclLinks.ids(policyIds, "a role's policy link");
        nodeIdentities = AclLinks.sorted(nodeIdentities);
        serviceIdentities = AclLinks.sorted(serviceIdentities);
    }

    /** This role linking only to the policies in {@code policies}. */
    AclRole withLinksIn(Set<String> policies) {
        return new AclRole(id, name, description, policyIds.stream().filter(policies::contains).toList(),
                nodeIdentities, serviceIdentities);
    }
}
