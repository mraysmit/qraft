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

        DockerLogCapture.Audit audit = DockerLogCapture.audit(
                log, Set.of(IntentionalError.RAFT_PEER_UNREACHABLE));

        assertEquals(1, audit.recognised().size());
        assertEquals(IntentionalError.RAFT_PEER_UNREACHABLE, audit.recognised().getFirst().error());
        assertTrue(audit.problems().isEmpty());
    }

    @Test
    void undeclaredContainerErrorIsReportedWithItsOriginalLine() {
        String line = "2026-10-06 14:00:00.000 [main] ERROR example.Container - unexpected failure";

        DockerLogCapture.Audit audit = DockerLogCapture.audit(line, Set.of());

        assertTrue(audit.recognised().isEmpty());
        assertEquals(java.util.List.of("undeclared container " + line), audit.problems());
    }

    @Test
    void anErrorShapedLineThatCannotBeParsedIsNeverSilentlyIgnored() {
        DockerLogCapture.Audit audit = DockerLogCapture.audit(
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

        DockerLogCapture.Audit audit = DockerLogCapture.audit(log, Set.of(
                IntentionalError.RAFT_SNAPSHOT_TRANSFER_INTERRUPTED,
                IntentionalError.CONTROLLER_RECOVERY_AMBIGUOUS_CORRUPTION,
                IntentionalError.WAL_DIRECTORY_ALREADY_LOCKED,
                IntentionalError.CONTROLLER_STORAGE_ALREADY_LOCKED));

        assertEquals(5, audit.recognised().size());
        assertTrue(audit.problems().isEmpty());
    }

    @Test
    void classCaptureArchivesOnlyTheUndrainedSuffixAndReprintsItsDeclaredError() throws Exception {
        String first = "2026-10-06 14:00:00.000 [main] INFO  example.Container - started\n";
        String error = "2026-10-06 14:00:01.000 [main] ERROR dev.mars.qraft.controller.raft.RaftNode - Raft peer n2 became unreachable during AppendEntries\n";
        DockerLogCapture.beginClass("FirstDockerClass", Set.of());
        DockerLogCapture.capture("container-1", "controller1", first);
        assertTrue(DockerLogCapture.finishClass(directory).isEmpty());

        DockerLogCapture.beginClass("SecondDockerClass", Set.of(IntentionalError.RAFT_PEER_UNREACHABLE));
        DockerLogCapture.capture("container-1", "controller1", first + error);
        assertTrue(DockerLogCapture.finishClass(directory).isEmpty());

        assertEquals(error, Files.readString(directory.resolve("SecondDockerClass/controller1.log")));

        DockerLogCapture.beginClass("QuietDockerClass", Set.of());
        DockerLogCapture.capture("container-1", "controller1", first + error);
        assertTrue(DockerLogCapture.finishClass(directory).isEmpty());
        assertEquals("", Files.readString(directory.resolve("QuietDockerClass/controller1.log")));
    }
}
