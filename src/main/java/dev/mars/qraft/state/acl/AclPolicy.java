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

import dev.mars.qraft.common.acl.AclRules;

import java.util.regex.Pattern;

/**
 * A policy: an ID, a name unique regardless of case, a description, and its rules (ACL list, proposal 8).
 *
 * <p>A name follows Consul's {@code ValidatePolicyName} in {@code acl/validation.go}: 1 to 128 letters, digits,
 * {@code -} and {@code _}, with at most one {@code /} that does not begin it, and never beginning {@code builtin/},
 * which Consul keeps for its own policies. A built-in policy may keep its own reserved name.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-04
 * @version 1.0
 */
public record AclPolicy(String id, String name, String description, AclRules rules) {
    static final int NAME_MAX_LENGTH = 128;
    static final String RESERVED_NAME_PREFIX = "builtin/";
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9\\-_]+/?[A-Za-z0-9\\-_]*");

    public AclPolicy {
        id = AclSecrets.canonicalId(id, "a policy's ID");
        if (!name(id, name)) throw new IllegalArgumentException("not a valid policy name: " + name);
        description = description == null ? "" : description;
        rules = rules == null ? AclRules.EMPTY : rules;
    }

    private static boolean name(String id, String name) {
        if (name != null && name.equals(AclBuiltins.reservedName(id))) return true;
        return name != null && !name.isEmpty() && name.length() <= NAME_MAX_LENGTH
                && !name.startsWith(RESERVED_NAME_PREFIX) && VALID_NAME.matcher(name).matches();
    }
}
