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

import java.util.Locale;
import java.util.Objects;

/**
 * The refusal of a request, in the words of Consul's {@code PermissionDeniedError}, which a server and a client both
 * answer: the accessor ID, the permission lacked, and what it is lacked on. It never holds a secret.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-07
 * @version 1.0
 */
public final class AclDenial {
    private AclDenial() {
    }

    /** The message for {@code request} refused to the token {@code accessorId}. */
    public static String message(String accessorId, AclRequest request) {
        Objects.requireNonNull(request, "request");
        StringBuilder message = new StringBuilder("Permission denied: token with AccessorID '").append(accessorId)
                .append("' lacks permission '").append(request.resource().ruleName()).append(':')
                .append(request.access().name().toLowerCase(Locale.ROOT)).append('\'');
        if (request.name() != null) message.append(" on \"").append(request.name()).append('"');
        if (request.resource().scoped()) {
            message.append(" in tenant \"").append(request.tenant()).append("\" namespace \"")
                    .append(request.namespace()).append('"');
        }
        return message.toString();
    }
}
