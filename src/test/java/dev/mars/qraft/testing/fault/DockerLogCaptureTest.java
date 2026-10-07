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

package dev.mars.qraft.testing.fault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the Docker log audit without requiring Docker. */
class DockerLogCaptureTest {

    @TempDir
    Path directory;

    @Test
    void declaredContainerErrorIsRecognisedAndAnExceptionContinuationIsNotASecondEvent() {
        String log = """
                2026-10-06 14:00:00.000 [qraft-state-loop] ERROR dev.mars.qraft.controller.raft.RaftNode [n1] - Raft peer n2 became unreachable during AppendEntries
                java.net.ConnectException: connection refused
                \tat example.Trace.call(Trace.java:1)
                """;

        DockerLogCaptureHelper.Audit audit = DockerLogCaptureHelper.audit(
                log, Set.of(IntentionalErrorFixture.RAFT_PEER_UNREACHABLE));

        assertEquals(1, audit.recognised().size());
        assertEquals(IntentionalErrorFixture.RAFT_PEER_UNREACHABLE, audit.recognised().getFirst().error());
        assertTrue(audit.problems().isEmpty());
    }

    @Test
    void archivesFlagDeclaredErrorsAndPreserveOtherLinesAndStackTraces() {
        String prefix = "2026-10-06 14:00:00.000 [qraft-state-loop] ERROR "
                + "dev.mars.qraft.controller.raft.RaftNode [n1] [LEADER] [term=2] - ";
        String message = "Raft peer n2 became unreachable during AppendEntries";
        String continuation = "\r\njava.net.ConnectException: connection refused\r\n"
                + "\tat example.Trace.call(Trace.java:1)\r\n";
        String unexpected = "2026-10-06 14:00:01.000 [main] ERROR example.Container - unexpected failure\n";
        String info = "2026-10-06 14:00:02.000 [main] INFO  example.Container - stopping\n";
        String unparseable = "12:00:00 | ERROR | runtime failed before Logback started";
        DockerLogCaptureHelper.Audit audit = DockerLogCaptureHelper.audit(
                prefix + message + continuation + unexpected + info + unparseable,
                Set.of(IntentionalErrorFixture.RAFT_PEER_UNREACHABLE), "DockerClass");

        assertEquals(prefix.replace("ERROR ", "ERROR *** INTENTIONAL ERROR: RAFT_PEER_UNREACHABLE, "
                        + "caused by DockerClass *** ")
                + message + continuation + unexpected + info + unparseable, audit.archivedOutput());
        assertEquals(1, audit.recognised().size());
        assertEquals(2, audit.problems().size());
        assertTrue(audit.problems().getFirst().startsWith("undeclared container "));
        assertTrue(audit.problems().getLast().startsWith("unparseable container ERROR: "));
    }

    @Test
    void archiveLabellingPreservesAnsiSequencesWithoutLosingTheIntentionalFlag() {
        String line = "2026-10-06 14:00:00.000 [main] \u001B[31mERROR\u001B[0m "
                + "dev.mars.qraft.controller.raft.RaftNode - "
                + "Raft peer n2 became unreachable during AppendEntries\n";
        DockerLogCaptureHelper.Audit audit = DockerLogCaptureHelper.audit(line,
                Set.of(IntentionalErrorFixture.RAFT_PEER_UNREACHABLE), "DockerClass");
        assertTrue(audit.problems().isEmpty());
        assertEquals(line.replace("\u001B[0m ", "\u001B[0m *** INTENTIONAL ERROR: RAFT_PEER_UNREACHABLE, "
                + "caused by DockerClass *** "), audit.archivedOutput());
    }

    @Test
    void anUndeclaredErrorIsArchivedUnflaggedAndFailsTheClassAudit() throws Exception {
        String error = "2026-10-06 14:00:00.000 [main] ERROR example.Container - unexpected failure\n";
        DockerLogCaptureHelper.beginClass("UnexpectedDockerClass", Set.of());
        DockerLogCaptureHelper.capture("unexpected-container", "controller1", error);
        assertEquals(java.util.List.of("controller1: undeclared container " + error.stripTrailing()),
                DockerLogCaptureHelper.finishClass(directory));
        assertEquals(error, Files.readString(directory.resolve("UnexpectedDockerClass/controller1.log")));
    }

    @Test
    void undeclaredContainerErrorIsReportedWithItsOriginalLine() {
        String line = "2026-10-06 14:00:00.000 [main] ERROR example.Container - unexpected failure";

        DockerLogCaptureHelper.Audit audit = DockerLogCaptureHelper.audit(line, Set.of());

        assertTrue(audit.recognised().isEmpty());
        assertEquals(java.util.List.of("undeclared container " + line), audit.problems());
    }

    @Test
    void anErrorShapedLineThatCannotBeParsedIsNeverSilentlyIgnored() {
        DockerLogCaptureHelper.Audit audit = DockerLogCaptureHelper.audit(
                "12:00:00 | ERROR | runtime failed before Logback started", Set.of());

        assertEquals(1, audit.problems().size());
        assertTrue(audit.problems().getFirst().contains("unparseable container ERROR"));
    }

    @Test
    void dockerRecoveryErrorsHaveNarrowIntentionalSignatures() {
        String log = """
                2026-10-06 14:00:00.000 [qraft-state-loop] ERROR dev.mars.qraft.controller.raft.RaftNode [n1] - Failed to send InstallSnapshot chunk 1/1 to n2: UNAVAILABLE: io exception
                2026-10-06 14:00:01.000 [qraft-state-loop] ERROR dev.mars.qraft.controller.raft.RaftNode [n1] - Failed to process InstallSnapshot response from n2: Raft transition sequencer is draining
                2026-10-06 14:00:02.000 [wal-executor] ERROR d.mars.qraft.controller.QraftControllerService - Raft recovery failed; node remains live but unready and will not participate. Preserve the node directory for diagnosis, then replace it from a healthy peer and restart: WAL /app/data/raft.log is corrupt at byte 0 of 510; 0 entries precede the damage. Not repaired: restore this node from its peers.
                2026-10-06 14:00:03.000 [wal-executor] ERROR dev.mars.raftlog.storage.FileRaftStorage - Cannot acquire exclusive lock at /app/data/raft.lock: another process holds the lock
                2026-10-06 14:00:04.000 [main] ERROR d.mars.qraft.controller.QraftControllerService - Failed to initialize Raft storage: Cannot acquire exclusive lock on WAL directory: /app/data. Another process may be using this storage.
                """;

        DockerLogCaptureHelper.Audit audit = DockerLogCaptureHelper.audit(log, Set.of(
                IntentionalErrorFixture.RAFT_SNAPSHOT_TRANSFER_INTERRUPTED,
                IntentionalErrorFixture.CONTROLLER_RECOVERY_AMBIGUOUS_CORRUPTION,
                IntentionalErrorFixture.WAL_DIRECTORY_ALREADY_LOCKED,
                IntentionalErrorFixture.CONTROLLER_STORAGE_ALREADY_LOCKED));

        assertEquals(5, audit.recognised().size());
        assertTrue(audit.problems().isEmpty());
    }

    @Test
    void classCaptureArchivesOnlyTheUndrainedSuffixAndReprintsItsDeclaredError() throws Exception {
        String first = "2026-10-06 14:00:00.000 [main] INFO  example.Container - started\n";
        String error = "2026-10-06 14:00:01.000 [main] ERROR dev.mars.qraft.controller.raft.RaftNode - Raft peer n2 became unreachable during AppendEntries\n";
        DockerLogCaptureHelper.beginClass("FirstDockerClass", Set.of());
        DockerLogCaptureHelper.capture("container-1", "controller1", first);
        assertTrue(DockerLogCaptureHelper.finishClass(directory).isEmpty());

        DockerLogCaptureHelper.beginClass("SecondDockerClass", Set.of(IntentionalErrorFixture.RAFT_PEER_UNREACHABLE));
        DockerLogCaptureHelper.capture("container-1", "controller1", first + error);
        assertTrue(DockerLogCaptureHelper.finishClass(directory).isEmpty());

        assertEquals(error.replace("ERROR ", "ERROR *** INTENTIONAL ERROR: RAFT_PEER_UNREACHABLE, "
                        + "caused by SecondDockerClass *** "),
                Files.readString(directory.resolve("SecondDockerClass/controller1.log")));

        DockerLogCaptureHelper.beginClass("QuietDockerClass", Set.of());
        DockerLogCaptureHelper.capture("container-1", "controller1", first + error);
        assertTrue(DockerLogCaptureHelper.finishClass(directory).isEmpty());
        assertEquals("", Files.readString(directory.resolve("QuietDockerClass/controller1.log")));
    }
}
