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

import java.time.Instant;
import java.util.List;

/**
 * The built-in policies and the anonymous token, with Consul's IDs, names and secret ({@code agent/structs/acl.go}
 * and {@code acl/acl.go}). They are part of every server's initial state, so they need no command and are the same
 * on every replica; Consul has its leader create the anonymous token when it gains leadership. A built-in policy can
 * be renamed and described, but not deleted, and its rules cannot change. The anonymous token can have policies
 * attached, and cannot be deleted.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-04
 * @version 1.0
 */
public final class AclBuiltins {
    public static final String GLOBAL_MANAGEMENT_ID = "00000000-0000-0000-0000-000000000001";
    public static final String GLOBAL_MANAGEMENT_NAME = "global-management";
    public static final String GLOBAL_READ_ONLY_ID = "00000000-0000-0000-0000-000000000002";
    public static final String GLOBAL_READ_ONLY_NAME = "builtin/global-read-only";
    /** The anonymous token's accessor ID, which is the read-only policy's ID too, as in Consul. */
    public static final String ANONYMOUS_TOKEN_ID = "00000000-0000-0000-0000-000000000002";
    /** Consul's secret for the anonymous token: a word, not a UUID, so it can never be a generated secret. */
    public static final String ANONYMOUS_SECRET = "anonymous";

    private static final List<AclPolicy> POLICIES = List.of(
            new AclPolicy(GLOBAL_MANAGEMENT_ID, GLOBAL_MANAGEMENT_NAME,
                    "A built-in policy that grants read and write access to all Qraft features",
                    AclRules.GLOBAL_MANAGEMENT),
            new AclPolicy(GLOBAL_READ_ONLY_ID, GLOBAL_READ_ONLY_NAME,
                    "A built-in policy that grants read-only access to all Qraft features", AclRules.GLOBAL_READ_ONLY));
    private static final AclToken ANONYMOUS = new AclToken(ANONYMOUS_TOKEN_ID, AclSecrets.hash(ANONYMOUS_SECRET),
            "Anonymous Token", List.of(), List.of(), List.of(), List.of(), Instant.EPOCH, null, false);

    private AclBuiltins() {
    }

    /** The built-in policies, in ID order, as they are before anyone renames or describes them. */
    public static List<AclPolicy> policies() {
        return POLICIES;
    }

    /** The anonymous token, as it is before anyone attaches policies to it. */
    public static AclToken anonymousToken() {
        return ANONYMOUS;
    }

    /** Whether {@code id} is a built-in policy's. */
    public static boolean isBuiltInPolicy(String id) {
        return GLOBAL_MANAGEMENT_ID.equals(id) || GLOBAL_READ_ONLY_ID.equals(id);
    }

    /** The built-in policy {@code id}, as it is before anyone renames it. */
    static AclPolicy builtInPolicy(String id) {
        return POLICIES.stream().filter(policy -> policy.id().equals(id)).findFirst().orElseThrow();
    }

    /** The reserved name the built-in policy {@code id} may keep, or {@code null} for any other policy. */
    static String reservedName(String id) {
        return GLOBAL_READ_ONLY_ID.equals(id) ? GLOBAL_READ_ONLY_NAME : null;
    }
}
