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
 * What decides a request that no rule of a token matches (ACL list, proposal 12): {@code allow} or {@code deny}, as
 * Consul's {@code acl.default_policy}. Under either, {@code acl} is refused without a rule for it, as Consul's static
 * allow-all authorizer refuses it ({@code acl/static_authorizer.go}).
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-05
 * @version 1.0
 */
public enum AclDefaultPolicy {
    ALLOW, DENY
}
