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

/**
 * Why the state machine refused an ACL command. Every replica refuses the same command for the same reason, and
 * nothing changes. The code is the one the API's error envelope will carry (ACL list, Phase 5).
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-04
 * @version 1.0
 */
public enum AclRefusal {
    /** Another token has the accessor ID. */
    ACCESSOR_IN_USE("accessor_in_use"),
    /** Another token has the secret: Consul's "SecretID is already in use". */
    SECRET_IN_USE("secret_in_use"),
    /** The ID lies in the range kept for built-ins. */
    RESERVED_ID("reserved_id"),
    /** Another policy or role has the ID. */
    ID_IN_USE("id_in_use"),
    /** Another policy or role has the name, regardless of case. */
    NAME_IN_USE("name_in_use"),
    /** A link names a policy that does not exist. */
    POLICY_NOT_FOUND("policy_not_found"),
    /** A link names a role that does not exist. */
    ROLE_NOT_FOUND("role_not_found"),
    /** An update changes a token's secret: Consul's "Changing a token's SecretID is not permitted". */
    SECRET_IMMUTABLE("secret_immutable"),
    /** An update changes a token's expiry: Consul's "Cannot change expiration time". */
    EXPIRY_IMMUTABLE("expiry_immutable"),
    /** The command deletes a built-in, or changes a built-in policy's rules. */
    BUILT_IN("builtin_immutable"),
    /** After the command, no token would hold {@code acl:write} without an expiry (proposal 17). */
    ACL_LOCKOUT("acl_lockout"),
    /** The bootstrap has been done, and the command gives no reset index. */
    ACL_BOOTSTRAPPED("acl_bootstrapped"),
    /** The reset index is not the current bootstrap index. */
    INVALID_RESET_INDEX("invalid_reset_index");

    private final String code;

    AclRefusal(String code) {
        this.code = code;
    }

    /** The code for this refusal in an answer. */
    public String code() {
        return code;
    }
}
