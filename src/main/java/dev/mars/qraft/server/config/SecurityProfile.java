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

package dev.mars.qraft.server.config;

/**
 * {@code server.security.profile} (ACL list, proposal 16). {@code production} refuses to start without ACLs enabled
 * and a {@code deny} default policy; {@code development}, the default, may run without ACLs, and then says so at
 * startup. With ACLs enabled the two behave alike, except that development also allows {@code allow}. Client mode
 * has no profile: a client follows what the servers enforce.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-05
 * @version 1.0
 */
public enum SecurityProfile {
    PRODUCTION, DEVELOPMENT
}
