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

    /** The time a moved file is named for, in UTC, to the millisecond, so the names sort as the times do. */
    private static final java.time.format.DateTimeFormatter STAMP =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSS'Z'").withZone(java.time.ZoneOffset.UTC);

    private final EventJournal journal;
    private final Path file;
    private final Rotation rotation;
    private final java.time.Clock clock;
    /** The file's name before its extension, and the extension, which a moved file keeps around its time. */
    private final String stem;
    private final String extension;
    private final java.util.regex.Pattern moved;
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong rotationFailures = new AtomicLong();
    private volatile boolean closed;
    private volatile EventSubscription subscription;

    private AuditFileSink(EventJournal journal, Path file, Rotation rotation, java.time.Clock clock) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.file = Objects.requireNonNull(file, "file");
        this.rotation = Objects.requireNonNull(rotation, "rotation");
        this.clock = Objects.requireNonNull(clock, "clock");
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        this.stem = dot > 0 ? name.substring(0, dot) : name;
        this.extension = dot > 0 ? name.substring(dot) : "";
        this.moved = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(stem)
                + "-(\\d{8}T\\d{6}\\.\\d{3}Z)(?:-(\\d+))?" + java.util.regex.Pattern.quote(extension));
    }

    /**
     * When the file is moved aside: before a line that would take it past {@code rotateBytes}; and how many moved
     * files are kept, {@code maxFiles}, the oldest removed first (open-items task list, decision 4).
     */
    public record Rotation(long rotateBytes, int maxFiles) {
        /** Consul's audit file sink example: 24 MiB a file and 15 files kept. */
        public static final Rotation DEFAULT = new Rotation(25_165_824, 15);
    }

    /** A sink that writes {@code journal}'s audit events from now on to {@code file}, rotated by {@code rotation}. */
    public static AuditFileSink start(EventJournal journal, Path file, Rotation rotation, java.time.Clock clock) {
        AuditFileSink sink = new AuditFileSink(journal, file, rotation, clock);
        EventCursor now = journal.recent(EventCursor.start(journal.bootId()), EventFilter.all(), Integer.MAX_VALUE).next();
        sink.follow(now.sequence() == 0 ? EventCursor.start(journal.bootId()) : now);
        return sink;
    }

    /** A sink that writes {@code journal}'s audit events from now on to {@code file}, rotated as by default. */
    public static AuditFileSink start(EventJournal journal, Path file) {
        return start(journal, file, Rotation.DEFAULT, java.time.Clock.systemUTC());
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
        line.put("tenant", request.tenant());
        line.put("namespace", request.namespace());
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
            byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
            if (Files.exists(file)) {
                long size = Files.size(file);
                if (size > 0 && size + bytes.length > rotation.rotateBytes()) rotate();
            }
            Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException failure) {
            if (failures.getAndIncrement() == 0) {
                LOG.warn("The audit file {} cannot be written, and audit lines are being lost: {}", file,
                        failure.toString());
            }
        }
    }

    /**
     * Moves the file aside, named for the time, and removes the oldest moved files past {@code maxFiles}. A file that
     * cannot be moved, as Windows refuses one a reader holds open, takes the line, and is moved at a later one: no line
     * is lost for it.
     */
    private void rotate() {
        String stamp = STAMP.format(clock.instant());
        Path target = file.resolveSibling(stem + "-" + stamp + extension);
        for (int same = 1; Files.exists(target); same++) {
            target = file.resolveSibling(stem + "-" + stamp + "-" + same + extension);
        }
        try {
            Files.move(file, target);
        } catch (IOException refused) {
            if (rotationFailures.getAndIncrement() == 0) {
                LOG.warn("The audit file {} could not be moved aside, so it goes on growing until it can be: {}", file,
                        refused.toString());
            }
            return;
        }
        prune();
    }

    /** Removes the oldest moved files, by the time each is named for, until {@code maxFiles} are left. */
    private void prune() {
        java.util.List<java.util.Map.Entry<String, Path>> kept = new java.util.ArrayList<>();
        Path directory = file.toAbsolutePath().getParent();
        try (var listed = Files.list(directory)) {
            listed.forEach(path -> {
                java.util.regex.Matcher name = moved.matcher(path.getFileName().toString());
                if (name.matches()) {
                    String same = name.group(2) == null ? "" : name.group(2);
                    // A second move in the same millisecond is numbered after the first, and sorts after it.
                    kept.add(java.util.Map.entry(name.group(1) + String.format("%010d", same.isEmpty() ? 0
                            : Integer.parseInt(same)), path));
                }
            });
        } catch (IOException unlisted) {
            return;
        }
        kept.sort(java.util.Map.Entry.comparingByKey());
        for (int oldest = 0; oldest < kept.size() - rotation.maxFiles(); oldest++) {
            try {
                Files.deleteIfExists(kept.get(oldest).getValue());
            } catch (IOException held) {
                // Windows refuses to remove a file a reader holds open; it is removed at the next move.
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
