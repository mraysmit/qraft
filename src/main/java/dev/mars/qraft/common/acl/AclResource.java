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

/**
 * The resources ACL rules control (ACL list, proposal 6). {@code client} is Consul's {@code agent}, under Qraft's
 * name. {@code node}, {@code service} and {@code client} rules name a resource exactly or by prefix; {@code operator}
 * and {@code acl} are single values, as in Consul. Only a {@code service} rule has a tenant and a namespace, because
 * nodes have none (proposal 7). {@code key} and {@code session} are left to the lists that add them.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-04
 * @version 1.0
 */
public enum AclResource {
    NODE(true, false),
    SERVICE(true, true),
    CLIENT(true, false),
    OPERATOR(false, false),
    ACL(false, false);

    private final boolean named;
    private final boolean scoped;

    AclResource(boolean named, boolean scoped) {
        this.named = named;
        this.scoped = scoped;
    }

    /** Whether a rule for this resource names it, exactly or by prefix. */
    public boolean named() {
        return named;
    }

    /** Whether a rule for this resource applies in a tenant and a namespace. */
    public boolean scoped() {
        return scoped;
    }

    /** The resource's name in rules, as Consul writes it. */
    public String ruleName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
