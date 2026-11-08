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

import dev.mars.qraft.common.events.EventCategory;
import dev.mars.qraft.common.events.EventEnvelope;
import dev.mars.qraft.common.events.EventJournal;
import dev.mars.qraft.common.events.EventSeverity;
import dev.mars.qraft.common.events.QraftEvent;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The server's audit events (authentication list, Phase 3, decision 4; architecture section 4.4): a request answered
 * under a token, with the token's accessor ID, the method, the route it was registered under and the status, and the
 * request ID as the envelope's correlation ID. Neither the secret nor a body is ever part of one.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public final class AuditEvents {
    private AuditEvents() { }

    /** A request answered under the token {@code accessorId}. */
    public record Request(String accessorId, String method, String route, int status) implements QraftEvent {
        public Request {
            Objects.requireNonNull(accessorId, "accessorId");
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(route, "route");
        }

        @Override
        public String type() {
            return "audit.http_request";
        }
    }

    /** Records {@code request} in {@code journal} as an audit event at {@code term}. */
    public static EventEnvelope publish(EventJournal journal, long term, Request request, String requestId) {
        return journal.publish(id -> new EventEnvelope(id, Clock.systemUTC().instant(), EventCategory.AUDIT,
                EventSeverity.INFO, term, OptionalLong.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.ofNullable(requestId), request));
    }
}
