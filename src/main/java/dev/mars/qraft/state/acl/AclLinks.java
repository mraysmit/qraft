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

import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Holds the links and identities of tokens and roles in one order, sorted and distinct, so that every replica encodes
 * them the same and no caller's collection is shared with replicated state.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-04
 * @version 1.0
 */
final class AclLinks {
    private AclLinks() {
    }

    /** {@code ids}, each a UUID in lower case, sorted and distinct; {@code null} is none. */
    static List<String> ids(List<String> ids, String what) {
        return sorted(ids == null ? null : ids.stream().map(id -> AclSecrets.canonicalId(id, what)).toList());
    }

    /** {@code values} sorted and distinct; {@code null} is none. */
    static <T extends Comparable<? super T>> List<T> sorted(List<T> values) {
        if (values == null) return List.of();
        values.forEach(value -> Objects.requireNonNull(value, "a link or identity"));
        return List.copyOf(new TreeSet<>(values));
    }
}
