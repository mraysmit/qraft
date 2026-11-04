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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests the node and service identities of the ACL list's proposal 9: each names what it grants, a service
 * identity outside a tenant and namespace is in {@code default}, and identities sort in one order.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-04
 * @version 1.0
 */
class AclIdentityTest {

    @Test
    void aNodeIdentityNamesANodeAsNodeNamesAreWritten() {
        // Qraft's node names default to the host name and may hold a dot, a capital or a slash, so the identity
        // takes any name a node can register under, not Consul's lower-case DNS label.
        assertEquals("Web-1.example.com/a", new NodeIdentity("Web-1.example.com/a").nodeName());

        assertThrows(IllegalArgumentException.class, () -> new NodeIdentity(null));
        assertThrows(IllegalArgumentException.class, () -> new NodeIdentity(" "));
        assertThrows(IllegalArgumentException.class, () -> new NodeIdentity("web\n1"));
        assertThrows(IllegalArgumentException.class, () -> new NodeIdentity("n".repeat(257)),
                "Consul's limit is 256 characters");
    }

    @Test
    void aServiceIdentityOutsideATenantAndNamespaceIsInDefault() {
        assertEquals(new ServiceIdentity("billing", "default", "default"), new ServiceIdentity("billing", null, null));
        assertEquals("acme", new ServiceIdentity("billing", "acme", "prod").tenant());

        assertThrows(IllegalArgumentException.class, () -> new ServiceIdentity("", null, null));
        assertThrows(IllegalArgumentException.class, () -> new ServiceIdentity("billing", " ", null));
        assertThrows(IllegalArgumentException.class, () -> new ServiceIdentity("bill\u0007ing", null, null));
    }

    @Test
    void identitiesSortInOneOrder() {
        assertEquals(List.of(new NodeIdentity("a"), new NodeIdentity("b")),
                List.of(new NodeIdentity("b"), new NodeIdentity("a")).stream().sorted().toList());
        assertEquals(List.of(new ServiceIdentity("web", "acme", "prod"), new ServiceIdentity("api", "default", "a"),
                        new ServiceIdentity("api", "default", "b")),
                List.of(new ServiceIdentity("api", "default", "b"), new ServiceIdentity("web", "acme", "prod"),
                        new ServiceIdentity("api", "default", "a")).stream().sorted().toList(),
                "by tenant, namespace, then name");
    }
}
