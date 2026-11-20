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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.qraft.common.events.EventJournal;
import dev.mars.qraft.common.events.EventSeverity;
import dev.mars.qraft.common.events.QraftEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the audit file sink (authentication list, Phase 3, decision 5): each audit event in the journal becomes one
 * JSON line in the file, and other events none; a file that cannot be written is reported, and the journal goes on.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-11-08
 * @version 1.0
 */
class AuditFileSinkTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path directory;

    private record Other() implements QraftEvent {
        @Override
        public String type() {
            return "test.other";
        }
    }

    @Test
    void eachAuditEventIsOneJsonLineAndOtherEventsAreLeftOut() throws Exception {
        Path file = directory.resolve("audit").resolve("qraft-audit.json");
        try (EventJournal journal = EventJournal.forBoot("server1", Clock.systemUTC());
             AuditFileSink sink = AuditFileSink.start(journal, file)) {
            journal.publishSystem(EventSeverity.INFO, 1, new Other());
            AuditEvents.publish(journal, 1, new AuditEvents.Request("a1b2", "PUT", "/v1/kv", 200, "acme", "prod"),
                    "req-1");
            AuditEvents.publish(journal, 1, new AuditEvents.Request("a1b2", "GET", "/v1/acl/tokens", 403), "req-2");

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while ((!Files.exists(file) || Files.readAllLines(file).size() < 2) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            List<String> lines = Files.readAllLines(file);
            assertEquals(2, lines.size(), String.join("\n", lines));
            JsonNode first = JSON.readTree(lines.getFirst());
            assertEquals("audit.http_request", first.path("type").asText());
            assertEquals("a1b2", first.path("accessorId").asText());
            assertEquals("/v1/kv", first.path("route").asText());
            assertEquals(200, first.path("status").asInt());
            assertEquals("req-1", first.path("requestId").asText());
            assertTrue(first.path("timestamp").asText().endsWith("Z"));
            assertEquals(403, JSON.readTree(lines.get(1)).path("status").asInt());
            // UI-6 Tenants, Namespaces and Audit task list, decision 4: each line names the request's scope.
            assertEquals("acme", first.path("tenant").asText());
            assertEquals("prod", first.path("namespace").asText());
            assertEquals("default", JSON.readTree(lines.get(1)).path("tenant").asText());
        }
    }

    /**
     * Open-items task list, decision 4: the file was never rotated, and the interface's live reads, each audited, grew
     * it by 34 kB a minute in the Chrome check of 2026-11-18. A line that would take the file past its limit first moves
     * the file aside, named for the time it was moved, and goes into a new one; an empty file is never moved.
     */
    @Test
    void aLineThatWouldTakeTheFilePastItsLimitMovesTheFileAsideUnderTheTimeAndGoesIntoANewOne() throws Exception {
        Path file = directory.resolve("qraft-audit.json");
        try (EventJournal journal = EventJournal.forBoot("server1", Clock.systemUTC());
             AuditFileSink sink = AuditFileSink.start(journal, file, new AuditFileSink.Rotation(1, 15), ticking())) {
            for (int request = 1; request <= 3; request++) publish(journal, "req-" + request);
            awaitLast(file, "req-3");
        }

        assertEquals(List.of("req-3"), requestIds(file));
        assertEquals(List.of("req-1"), requestIds(directory.resolve("qraft-audit-20261120T101530.000Z.json")));
        assertEquals(List.of("req-2"), requestIds(directory.resolve("qraft-audit-20261120T101531.000Z.json")));
    }

    @Test
    void onlyTheNewestMovedFilesAreKeptAndNoOtherFileIsTouched() throws Exception {
        Path file = directory.resolve("qraft-audit.json");
        Path other = Files.writeString(directory.resolve("qraft-audit-notes.json"), "kept");
        try (EventJournal journal = EventJournal.forBoot("server1", Clock.systemUTC());
             AuditFileSink sink = AuditFileSink.start(journal, file, new AuditFileSink.Rotation(1, 1), ticking())) {
            for (int request = 1; request <= 4; request++) publish(journal, "req-" + request);
            awaitLast(file, "req-4");
        }

        try (var listed = Files.list(directory)) {
            assertEquals(List.of("qraft-audit-20261120T101532.000Z.json", "qraft-audit-notes.json", "qraft-audit.json"),
                    listed.map(path -> path.getFileName().toString()).sorted().toList());
        }
        assertEquals(List.of("req-3"), requestIds(directory.resolve("qraft-audit-20261120T101532.000Z.json")));
        assertEquals("kept", Files.readString(other));
    }

    /** A clock that reads 2026-11-20T10:15:30Z and then a second later each time it is read. */
    private static Clock ticking() {
        return new Clock() {
            private java.time.Instant next = java.time.Instant.parse("2026-11-20T10:15:30Z");

            @Override
            public java.time.ZoneId getZone() {
                return java.time.ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public synchronized java.time.Instant instant() {
                java.time.Instant now = next;
                next = next.plusSeconds(1);
                return now;
            }
        };
    }

    private static void publish(EventJournal journal, String requestId) {
        AuditEvents.publish(journal, 1, new AuditEvents.Request("a1b2", "GET", "/v1/kv", 200), requestId);
    }

    private static void awaitLast(Path file, String requestId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while ((!Files.exists(file) || !Files.readString(file).contains(requestId)) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(Files.exists(file) && Files.readString(file).contains(requestId), requestId + " was not written");
    }

    private static List<String> requestIds(Path file) throws Exception {
        List<String> ids = new java.util.ArrayList<>();
        for (String line : Files.readAllLines(file)) ids.add(JSON.readTree(line).path("requestId").asText());
        return ids;
    }

    @Test
    void aFileThatCannotBeWrittenIsReportedAndTheJournalGoesOn() throws Exception {
        Path blocked = Files.writeString(directory.resolve("not-a-directory"), "x").resolve("audit.json");
        try (EventJournal journal = EventJournal.forBoot("server1", Clock.systemUTC());
             AuditFileSink sink = AuditFileSink.start(journal, blocked)) {
            AuditEvents.publish(journal, 1, new AuditEvents.Request("a1b2", "PUT", "/v1/kv", 200), "req-1");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (sink.failures() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(1, sink.failures(), "the write failed, and was counted");
            AuditEvents.publish(journal, 1, new AuditEvents.Request("a1b2", "GET", "/v1/kv", 200), "req-2");
            assertEquals(2, journal.recent(dev.mars.qraft.common.events.EventCursor.start(journal.bootId()),
                    dev.mars.qraft.common.events.EventFilter.all(), 10).events().size(), "publication goes on");
        }
    }
}
