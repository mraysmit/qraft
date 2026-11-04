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
 * The checks that the names in rules and identities share.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-04
 * @version 1.0
 */
final class AclNames {
    /** Consul's limit for an identity's name ({@code acl/validation.go}). */
    static final int IDENTITY_NAME_MAX_LENGTH = 256;

    private AclNames() {
    }

    /** Checks the name a rule or scope matches: an exact match cannot be blank, and a prefix may be empty. */
    static void requireMatch(String name, boolean prefix, String what) {
        if (name == null) throw new IllegalArgumentException(what + " needs a name");
        if (!prefix && name.isBlank()) throw new IllegalArgumentException(what + " cannot match a blank name exactly");
        refuseControlCharacters(name, what);
    }

    /** {@code name}, which must not be blank, hold a control character, or be longer than Consul allows. */
    static String identityName(String name, String what) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException(what + " must not be blank");
        if (name.length() > IDENTITY_NAME_MAX_LENGTH) {
            throw new IllegalArgumentException(what + " is longer than " + IDENTITY_NAME_MAX_LENGTH + " characters");
        }
        refuseControlCharacters(name, what);
        return name;
    }

    private static void refuseControlCharacters(String name, String what) {
        if (name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(what + " holds a control character");
        }
    }
}
