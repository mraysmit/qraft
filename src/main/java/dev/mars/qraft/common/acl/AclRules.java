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

import java.util.List;

/**
 * STUB
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-04
 * @version 1.0
 */
public record AclRules(List<AclRule> rules) {
    public static final AclRules GLOBAL_MANAGEMENT = new AclRules(List.of());
    public static final AclRules GLOBAL_READ_ONLY = new AclRules(List.of());
    public static AclAccess mergedAccess(AclResource resource, List<AclRules> policies) {
        return null;
    }
}
