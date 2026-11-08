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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.common.events.EventCategory;
import dev.mars.qraft.common.events.EventConsumer;
import dev.mars.qraft.common.events.EventCursor;
import dev.mars.qraft.common.events.EventEnvelope;
import dev.mars.qraft.common.events.EventFilter;
import dev.mars.qraft.common.events.EventJournal;
import dev.mars.qraft.common.events.EventSubscription;
import dev.mars.qraft.common.events.SubscriptionEnd;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes the journal's audit events to a file, one JSON line each (authentication list, Phase 3, decision 5), best
 * effort as Consul's default sink is: a line that cannot be written is counted, the first such failure is reported at
 * WARN without a stack trace, and the requests go on. A subscription that falls behind is resumed after the last line
 * written, or, when the journal no longer holds what followed, from the oldest event it holds, with a line saying
 * events were lost.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
public final class AuditFileSink implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(AuditFileSink.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final EventFilter AUDIT = new EventFilter(Set.of(EventCategory.AUDIT), Set.of(), Optional.empty(),
            Optional.empty());

    private final EventJournal journal;
    private final Path file;
    private final AtomicLong failures = new AtomicLong();
    private volatile boolean closed;
    private volatile EventSubscription subscription;

    private AuditFileSink(EventJournal journal, Path file) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.file = Objects.requireNonNull(file, "file");
    }

    /** A sink that writes {@code journal}'s audit events from now on to {@code file}. */
    public static AuditFileSink start(EventJournal journal, Path file) {
        AuditFileSink sink = new AuditFileSink(journal, file);
        EventCursor now = journal.recent(EventCursor.start(journal.bootId()), EventFilter.all(), Integer.MAX_VALUE).next();
        sink.follow(now.sequence() == 0 ? EventCursor.start(journal.bootId()) : now);
        return sink;
    }

    /** How many lines could not be written. */
    public long failures() {
        return failures.get();
    }

    private void follow(EventCursor after) {
        if (closed) return;
        subscription = journal.subscribe(after, AUDIT, new EventConsumer() {
            @Override
            public void accept(EventEnvelope event) {
                write(line(event));
            }

            @Override
            public void ended(SubscriptionEnd end) {
                switch (end.reason()) {
                    case OVERFLOW -> follow(end.resumeAfter());
                    case HISTORY_GAP -> {
                        write("{\"type\":\"audit.events_lost\"}");
                        follow(end.resumeAfter());
                    }
                    default -> { }
                }
            }
        });
    }

    private static String line(EventEnvelope event) {
        AuditEvents.Request request = (AuditEvents.Request) event.event();
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", request.type());
        line.put("timestamp", event.observedAt().toString());
        line.put("node", event.id().nodeId());
        line.put("accessorId", request.accessorId());
        line.put("method", request.method());
        line.put("route", request.route());
        line.put("status", request.status());
        line.put("requestId", event.correlationId().orElse(""));
        try {
            return JSON.writeValueAsString(line);
        } catch (IOException unwritable) {
            throw new IllegalStateException("An audit line could not be written as JSON", unwritable);
        }
    }

    private synchronized void write(String line) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException failure) {
            if (failures.getAndIncrement() == 0) {
                LOG.warn("The audit file {} cannot be written, and audit lines are being lost: {}", file,
                        failure.toString());
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        EventSubscription current = subscription;
        if (current != null) current.close();
    }
}
