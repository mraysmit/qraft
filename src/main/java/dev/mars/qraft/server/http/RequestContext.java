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

package dev.mars.qraft.server.http;

/**
 * The principal an HTTP request acts as, and the catalog identity it names: the scope and node it chose to act in,
 * which the principal's rules decide (ACL list, Phase 4).
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-09-24
 * @version 1.1
 */
interface RequestContext {
    /** Who the request acts as: its resolved token, or, with ACLs disabled, an unrestricted principal. */
    dev.mars.qraft.server.acl.AclPrincipal principal();

    String tenantId();

    String namespace();

    /** The node's name, under which the catalog keys it and what it registers. */
    String nodeId();

    /** The node's ID, the UUID its client keeps in its data directory, or {@code null} from a client without one. */
    String nodeUuid();
}
