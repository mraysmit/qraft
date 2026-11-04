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
 * STUB
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-04
 * @version 1.0
 */
public record AclRule(AclResource resource, String name, boolean prefix, AclAccess access, AclScope tenant,
                      AclScope namespace) {
    public static AclRule exact(AclResource resource, String name, AclAccess access) {
        return new AclRule(resource, name, false, access, null, null);
    }
    public static AclRule prefix(AclResource resource, String prefix, AclAccess access) {
        return new AclRule(resource, prefix, true, access, null, null);
    }
    public static AclRule single(AclResource resource, AclAccess access) {
        return new AclRule(resource, null, false, access, null, null);
    }
    public AclRule in(AclScope tenant, AclScope namespace) {
        return this;
    }
}
