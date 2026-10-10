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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the Docker log audit without requiring Docker. */
class DockerLogCaptureTest {

    private static final String LOCK_FAILURE = "Cannot acquire exclusive lock on WAL directory: /app/data."
            + " Another process may be using this storage.";
    private static final String SERVER_LOCK_ERROR = "2026-10-06 14:00:00.000 [wal-executor] ERROR "
            + "dev.mars.qraft.server.QraftServerService - Failed to initialize Raft storage: " + LOCK_FAILURE;
    private static final String UNCAUGHT_LOCK_FAILURE = "Exception in thread \"main\" "
            + "java.util.concurrent.CompletionException: dev.mars.raftlog.storage.FileRaftStorage$StorageException: "
            + LOCK_FAILURE;
    private static final String UNRESOLVED_PEER = "2026-10-10 06:03:32.783 [grpc-default-executor-2] WARN  "
            + "io.grpc.internal.ManagedChannelImpl - [Channel<10>: (server3:9080)] Failed to resolve name. "
            + "status=Status{code=UNAVAILABLE, description=Unable to resolve host server3, "
            + "cause=java.lang.RuntimeException: java.net.UnknownHostException: server3: Try again";
    private static final String LOOKUP_TRACE =
            "\n\tat io.grpc.internal.DnsNameResolver.resolveAddresses(DnsNameResolver.java:224)"
            + "\nCaused by: java.net.UnknownHostException: server3: Try again"
            + "\n\tat java.base/java.net.Inet6AddressImpl.lookupAllHostAddr(Native Method)"
            + "\n\t... 5 more\n}\n";

    @TempDir
    Path directory;

    @Test
    void declaredContainerErrorIsRecognisedAndAnExceptionContinuationIsNotASecondEvent() {
        String log = """
                2026-10-06 14:00:00.000 [qraft-state-loop] ERROR dev.mars.qraft.raft.RaftNode [n1] - Raft peer n2 became unreachable during AppendEntries
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
                + "dev.mars.qraft.raft.RaftNode [n1] [LEADER] [term=2] - ";
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
                + "dev.mars.qraft.raft.RaftNode - "
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
        DockerLogCaptureHelper.capture("unexpected-container", "server1", error);
        assertEquals(java.util.List.of("server1: undeclared container " + error.stripTrailing()),
                DockerLogCaptureHelper.finishClass(directory));
        assertEquals(error, Files.readString(directory.resolve("UnexpectedDockerClass/server1.log")));
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
    void theDeclaredLockConflictRethrownAfterCleanupIsFlaggedWithItsStackTracePreserved() {
        String cleanup = "2026-10-06 14:00:01.000 [main] INFO  "
                + "dev.mars.qraft.server.QraftServerService - QraftServerService stopped successfully (immediate)";
        String trace = "\r\n\tat java.base/java.util.concurrent.CompletableFuture.encodeThrowable(Unknown Source)"
                + "\r\nCaused by: dev.mars.raftlog.storage.FileRaftStorage$StorageException: " + LOCK_FAILURE
                + "\r\n\tat dev.mars.raftlog.storage.FileRaftStorage.acquireExclusiveLock(FileRaftStorage.java:1879)\r\n";
        String output = SERVER_LOCK_ERROR + "\r\n" + cleanup + "\r\n" + UNCAUGHT_LOCK_FAILURE + trace;

        DockerLogCaptureHelper.Audit audit = DockerLogCaptureHelper.audit(output,
                Set.of(IntentionalErrorFixture.SERVER_STORAGE_ALREADY_LOCKED), "LockConflictDockerClass");

        String label = "*** INTENTIONAL ERROR: SERVER_STORAGE_ALREADY_LOCKED, caused by LockConflictDockerClass *** ";
        assertTrue(audit.problems().isEmpty(), audit.problems().toString());
        assertEquals(1, audit.recognised().size(), "the uncaught rethrow must not replay the same error twice");
        assertEquals(SERVER_LOCK_ERROR.replace("ERROR ", "ERROR " + label)
                + "\r\n" + cleanup + "\r\n"
                + UNCAUGHT_LOCK_FAILURE.replace("Exception in thread \"main\" ", "Exception in thread \"main\" " + label)
                + trace, audit.archivedOutput());
    }

    @Test
    void anUncaughtExceptionOrErrorWithoutAnErrorLogIsStillAProblem() {
        for (String line : java.util.List.of(
                "Exception in thread \"worker\" java.lang.NullPointerException: unexpected failure",
                "Exception in thread \"main\" java.lang.AssertionError: unexpected failure")) {
            DockerLogCaptureHelper.Audit audit = DockerLogCaptureHelper.audit(line, Set.of(), "DockerClass");
            assertEquals(java.util.List.of("undeclared container uncaught exception: " + line), audit.problems());
            assertTrue(audit.recognised().isEmpty());
            assertEquals(line, audit.archivedOutput());
        }
    }

    @Test
    void anUncaughtLockFailureNeedsADeclaredMatchingError() {
        DockerLogCaptureHelper.Audit undeclared = DockerLogCaptureHelper.audit(
                SERVER_LOCK_ERROR + "\n" + UNCAUGHT_LOCK_FAILURE, Set.of(), "DockerClass");
        assertEquals(2, undeclared.problems().size());
        assertEquals(SERVER_LOCK_ERROR + "\n" + UNCAUGHT_LOCK_FAILURE, undeclared.archivedOutput());

        DockerLogCaptureHelper.Audit unmatched = DockerLogCaptureHelper.audit(UNCAUGHT_LOCK_FAILURE,
                Set.of(IntentionalErrorFixture.SERVER_STORAGE_ALREADY_LOCKED), "DockerClass");
        assertEquals(java.util.List.of("undeclared container uncaught exception: " + UNCAUGHT_LOCK_FAILURE),
                unmatched.problems());
        assertEquals(UNCAUGHT_LOCK_FAILURE, unmatched.archivedOutput());
    }

    @Test
    void anUncaughtRethrowBeforeItsErrorIsFlaggedWithoutReorderingTheOutput() {
        String output = UNCAUGHT_LOCK_FAILURE + "\r\n" + SERVER_LOCK_ERROR + "\r\n";
        DockerLogCaptureHelper.Audit audit = DockerLogCaptureHelper.audit(output,
                Set.of(IntentionalErrorFixture.SERVER_STORAGE_ALREADY_LOCKED), "DockerClass");
        String label = "*** INTENTIONAL ERROR: SERVER_STORAGE_ALREADY_LOCKED, caused by DockerClass *** ";

        assertEquals(java.util.List.of(), audit.problems());
        assertEquals(1, audit.recognised().size());
        assertEquals(UNCAUGHT_LOCK_FAILURE.replace("Exception in thread \"main\" ",
                        "Exception in thread \"main\" " + label) + "\r\n"
                        + SERVER_LOCK_ERROR.replace("ERROR ", "ERROR " + label) + "\r\n",
                audit.archivedOutput());
    }

    @Test
    void prefixedUncaughtExceptionsCannotEscapeTheAudit() {
        for (String prefix : java.util.List.of("server1 | ", "2026-10-08T16:00:00Z ",
                "server1 | 2026-10-08T16:00:00Z ")) {
            String line = prefix + "Exception in thread \"main\" java.lang.AssertionError: unexpected failure";
            DockerLogCaptureHelper.Audit audit = DockerLogCaptureHelper.audit(line, Set.of(), "DockerClass");
            assertEquals(java.util.List.of("undeclared container uncaught exception: " + line), audit.problems());
            assertEquals(line, audit.archivedOutput());

            String malformed = prefix + "Exception in thread main java.lang.AssertionError: missing quotes";
            assertEquals(java.util.List.of("unparseable container uncaught exception: " + malformed),
                    DockerLogCaptureHelper.audit(malformed, Set.of(), "DockerClass").problems());
        }
    }

    @Test
    void aPrefixedDeclaredRethrowKeepsItsPrefixAndStackTrace() {
        String header = "server1 | 2026-10-08T16:00:00Z " + UNCAUGHT_LOCK_FAILURE;
        String trace = "\nserver1 | \tat example.Trace.call(Trace.java:1)\n";
        DockerLogCaptureHelper.Audit audit = DockerLogCaptureHelper.audit(
                SERVER_LOCK_ERROR + "\n" + header + trace,
                Set.of(IntentionalErrorFixture.SERVER_STORAGE_ALREADY_LOCKED), "DockerClass");
        String label = "*** INTENTIONAL ERROR: SERVER_STORAGE_ALREADY_LOCKED, caused by DockerClass *** ";

        assertEquals(java.util.List.of(), audit.problems());
        assertEquals(SERVER_LOCK_ERROR.replace("ERROR ", "ERROR " + label) + "\n"
                        + header.replace("Exception in thread \"main\" ", "Exception in thread \"main\" " + label)
                        + trace, audit.archivedOutput());
    }

    @Test
    void aRethrowMatchesAnErrorPreviouslyDrainedFromTheSameSource() throws Exception {
        Set<IntentionalErrorFixture> expected = Set.of(IntentionalErrorFixture.SERVER_STORAGE_ALREADY_LOCKED);
        String source = "split-lock-rethrow-container";
        DockerLogCaptureHelper.beginClass("FirstDrainDockerClass", expected);
        DockerLogCaptureHelper.capture(source, "lock-contender", SERVER_LOCK_ERROR + "\n");
        assertEquals(java.util.List.of(), DockerLogCaptureHelper.finishClass(directory));

        DockerLogCaptureHelper.beginClass("SecondDrainDockerClass", expected);
        DockerLogCaptureHelper.capture(source, "lock-contender",
                SERVER_LOCK_ERROR + "\n" + UNCAUGHT_LOCK_FAILURE + "\n");
        assertEquals(java.util.List.of(), DockerLogCaptureHelper.finishClass(directory));

        String label = "*** INTENTIONAL ERROR: SERVER_STORAGE_ALREADY_LOCKED, caused by SecondDrainDockerClass *** ";
        assertEquals(UNCAUGHT_LOCK_FAILURE.replace("Exception in thread \"main\" ",
                        "Exception in thread \"main\" " + label) + "\n",
                Files.readString(directory.resolve("SecondDrainDockerClass/lock-contender.log")));
    }

    @Test
    void anErrorDrainedFromAnotherSourceCannotExcuseARethrow() throws Exception {
        Set<IntentionalErrorFixture> expected = Set.of(IntentionalErrorFixture.SERVER_STORAGE_ALREADY_LOCKED);
        DockerLogCaptureHelper.beginClass("OtherSourceFirstDrainDockerClass", expected);
        DockerLogCaptureHelper.capture("original-lock-container", "lock-contender", SERVER_LOCK_ERROR + "\n");
        assertEquals(java.util.List.of(), DockerLogCaptureHelper.finishClass(directory));

        DockerLogCaptureHelper.beginClass("OtherSourceRethrowDockerClass", expected);
        DockerLogCaptureHelper.capture("different-lock-container", "lock-contender", UNCAUGHT_LOCK_FAILURE + "\n");
        assertEquals(java.util.List.of("lock-contender: undeclared container uncaught exception: "
                        + UNCAUGHT_LOCK_FAILURE), DockerLogCaptureHelper.finishClass(directory));
        assertEquals(UNCAUGHT_LOCK_FAILURE + "\n",
                Files.readString(directory.resolve("OtherSourceRethrowDockerClass/lock-contender.log")));
    }

    @Test
    void aDeclaredLockErrorCannotExcuseAnUncaughtFailureWithAnotherCauseOrDirectory() {
        for (String line : java.util.List.of(
                UNCAUGHT_LOCK_FAILURE.replace("/app/data", "/app/other-data"),
                UNCAUGHT_LOCK_FAILURE.replace("FileRaftStorage$StorageException", "FileRaftStorage$OtherException"),
                UNCAUGHT_LOCK_FAILURE.replace("java.util.concurrent.CompletionException", "java.lang.IllegalStateException"),
                UNCAUGHT_LOCK_FAILURE + " and an unrelated failure",
                "Exception in thread \"main\" java.lang.NullPointerException: shutdown failed")) {
            DockerLogCaptureHelper.Audit audit = DockerLogCaptureHelper.audit(SERVER_LOCK_ERROR + "\n" + line,
                    Set.of(IntentionalErrorFixture.SERVER_STORAGE_ALREADY_LOCKED), "DockerClass");
            assertEquals(java.util.List.of("undeclared container uncaught exception: " + line), audit.problems());
            assertTrue(audit.archivedOutput().endsWith("\n" + line));
        }
    }

    @Test
    void aMalformedUncaughtExceptionHeaderIsNeverSilentlyIgnored() {
        for (String line : java.util.List.of(
                "Exception in thread main java.lang.NullPointerException: missing thread quotes",
                "Exception in thread")) {
            DockerLogCaptureHelper.Audit audit = DockerLogCaptureHelper.audit(line, Set.of(), "DockerClass");
            assertEquals(java.util.List.of("unparseable container uncaught exception: " + line), audit.problems());
            assertEquals(line, audit.archivedOutput());
        }
    }

    @Test
    void classCaptureArchivesTheDeclaredUncaughtLockExceptionWithItsFlag() throws Exception {
        String output = SERVER_LOCK_ERROR + "\n" + UNCAUGHT_LOCK_FAILURE + "\n";
        DockerLogCaptureHelper.beginClass("UncaughtLockDockerClass",
                Set.of(IntentionalErrorFixture.SERVER_STORAGE_ALREADY_LOCKED));
        DockerLogCaptureHelper.capture("lock-contender-container", "lock-contender", output);
        assertTrue(DockerLogCaptureHelper.finishClass(directory).isEmpty());

        String label = "*** INTENTIONAL ERROR: SERVER_STORAGE_ALREADY_LOCKED, caused by UncaughtLockDockerClass *** ";
        assertEquals(SERVER_LOCK_ERROR.replace("ERROR ", "ERROR " + label) + "\n"
                        + UNCAUGHT_LOCK_FAILURE.replace("Exception in thread \"main\" ", "Exception in thread \"main\" " + label)
                        + "\n",
                Files.readString(directory.resolve("UncaughtLockDockerClass/lock-contender.log")));
    }

    @Test
    void classCaptureArchivesAnUnexpectedUncaughtExceptionUnflaggedAndFailsItsAudit() throws Exception {
        String line = "Exception in thread \"main\" java.lang.NullPointerException: unexpected startup failure\n";
        DockerLogCaptureHelper.beginClass("UnexpectedUncaughtDockerClass", Set.of());
        DockerLogCaptureHelper.capture("unexpected-startup-container", "server1", line);
        assertEquals(java.util.List.of("server1: undeclared container uncaught exception: " + line.stripTrailing()),
                DockerLogCaptureHelper.finishClass(directory));
        assertEquals(line, Files.readString(directory.resolve("UnexpectedUncaughtDockerClass/server1.log")));
    }

    @Test
    void dockerRecoveryErrorsHaveNarrowIntentionalSignatures() {
        String log = """
                2026-10-06 14:00:00.000 [qraft-state-loop] ERROR dev.mars.qraft.raft.RaftNode [n1] - Failed to send InstallSnapshot chunk 1/1 to n2: UNAVAILABLE: io exception
                2026-10-06 14:00:01.000 [qraft-state-loop] ERROR dev.mars.qraft.raft.RaftNode [n1] - Failed to process InstallSnapshot response from n2: Raft transition sequencer is draining
                2026-10-06 14:00:02.000 [wal-executor] ERROR dev.mars.qraft.server.QraftServerService - Raft recovery failed; node remains live but unready and will not participate. Preserve the node directory for diagnosis, then replace it from a healthy peer and restart: WAL /app/data/raft.log is corrupt at byte 0 of 510; 0 entries precede the damage. Not repaired: restore this node from its peers.
                2026-10-06 14:00:03.000 [wal-executor] ERROR dev.mars.raftlog.storage.FileRaftStorage - Cannot acquire exclusive lock at /app/data/raft.lock: another process holds the lock
                2026-10-06 14:00:04.000 [main] ERROR dev.mars.qraft.server.QraftServerService - Failed to initialize Raft storage: Cannot acquire exclusive lock on WAL directory: /app/data. Another process may be using this storage.
                """;

        DockerLogCaptureHelper.Audit audit = DockerLogCaptureHelper.audit(log, Set.of(
                IntentionalErrorFixture.RAFT_SNAPSHOT_TRANSFER_INTERRUPTED,
                IntentionalErrorFixture.SERVER_RECOVERY_AMBIGUOUS_CORRUPTION,
                IntentionalErrorFixture.WAL_DIRECTORY_ALREADY_LOCKED,
                IntentionalErrorFixture.SERVER_STORAGE_ALREADY_LOCKED));

        assertEquals(5, audit.recognised().size());
        assertTrue(audit.problems().isEmpty());
    }

    @Test
    void aDeclaredWarningThatCarriesAStackTraceIsFlaggedWithItsTracePreserved() {
        String cached = UNRESOLVED_PEER.replace(": Try again", "");
        String info = "2026-10-10 06:03:33.000 [main] INFO  example.Container - still serving\n";
        DockerLogCaptureHelper.Audit audit = DockerLogCaptureHelper.audit(
                UNRESOLVED_PEER + LOOKUP_TRACE + info + cached + LOOKUP_TRACE,
                Set.of(IntentionalErrorFixture.RAFT_PEER_NAME_UNRESOLVED), "DockerClass");

        String label = "*** INTENTIONAL ERROR: RAFT_PEER_NAME_UNRESOLVED, caused by DockerClass *** ";
        assertEquals(java.util.List.of(), audit.problems());
        assertEquals(2, audit.recognised().size());
        assertEquals(UNRESOLVED_PEER.replace("WARN  ", "WARN  " + label) + LOOKUP_TRACE + info
                + cached.replace("WARN  ", "WARN  " + label) + LOOKUP_TRACE, audit.archivedOutput());
    }

    @Test
    void anUndeclaredEventBelowErrorThatCarriesAStackTraceIsAProblem() {
        for (String level : java.util.List.of("WARN ", "INFO ", "DEBUG")) {
            String line = "2026-10-10 06:03:32.783 [worker] " + level + " example.Container - lookup failed";
            String output = line + "\njava.net.UnknownHostException: server3\n\tat example.Trace.call(Trace.java:1)\n";
            DockerLogCaptureHelper.Audit audit = DockerLogCaptureHelper.audit(output, Set.of(), "DockerClass");
            assertEquals(java.util.List.of("undeclared container event with a stack trace: " + line),
                    audit.problems());
            assertEquals(output, audit.archivedOutput());
            assertTrue(audit.recognised().isEmpty());
        }
        DockerLogCaptureHelper.Audit undeclared = DockerLogCaptureHelper.audit(UNRESOLVED_PEER + LOOKUP_TRACE,
                Set.of(IntentionalErrorFixture.RAFT_PEER_UNREACHABLE), "DockerClass");
        assertEquals(java.util.List.of("undeclared container event with a stack trace: " + UNRESOLVED_PEER),
                undeclared.problems());
        assertEquals(UNRESOLVED_PEER + LOOKUP_TRACE, undeclared.archivedOutput());
    }

    @Test
    void anEventBelowErrorWithoutAStackTraceIsNotAProblem() {
        String output = """
                2026-10-10 06:03:32.000 [qraft-state-loop] ERROR dev.mars.qraft.raft.RaftNode [n1] - Raft peer n2 became unreachable during AppendEntries
                java.net.ConnectException: connection refused
                \tat example.Trace.call(Trace.java:1)
                2026-10-10 06:03:32.500 [main] WARN  example.Container - retrying at the next interval
                2026-10-10 06:03:33.000 [main] INFO  example.Container - recovered at index 12 (term 3)
                """;

        DockerLogCaptureHelper.Audit audit = DockerLogCaptureHelper.audit(
                output, Set.of(IntentionalErrorFixture.RAFT_PEER_UNREACHABLE));

        assertEquals(java.util.List.of(), audit.problems());
        assertEquals(1, audit.recognised().size());
    }

    @Test
    void framesAfterAnUncaughtExceptionBelongToItEvenWhenDockerMergesAnEventIntoItsTrace() {
        String cleanup = "2026-10-06 14:00:01.000 [main] INFO  "
                + "dev.mars.qraft.server.QraftServerService - QraftServerService stopped successfully (immediate)";
        String output = SERVER_LOCK_ERROR + "\n" + UNCAUGHT_LOCK_FAILURE
                + "\n\tat java.base/java.util.concurrent.CompletableFuture.encodeThrowable(Unknown Source)\n"
                + cleanup
                + "\nCaused by: dev.mars.raftlog.storage.FileRaftStorage$StorageException: " + LOCK_FAILURE
                + "\n\tat dev.mars.raftlog.storage.FileRaftStorage.acquireExclusiveLock(FileRaftStorage.java:1879)\n";

        DockerLogCaptureHelper.Audit audit = DockerLogCaptureHelper.audit(output,
                Set.of(IntentionalErrorFixture.SERVER_STORAGE_ALREADY_LOCKED), "DockerClass");

        assertEquals(java.util.List.of(), audit.problems());
    }

    @Test
    void theUnresolvedPeerWarningHasANarrowSignature() {
        Set<IntentionalErrorFixture> expected = Set.of(IntentionalErrorFixture.RAFT_PEER_NAME_UNRESOLVED);
        for (String line : java.util.List.of(
                UNRESOLVED_PEER.replace("UnknownHostException: server3", "UnknownHostException: server1"),
                UNRESOLVED_PEER.replace("Unable to resolve host server3", "Unable to resolve host server1"),
                UNRESOLVED_PEER.replace("code=UNAVAILABLE", "code=INTERNAL"),
                UNRESOLVED_PEER.replace("java.net.UnknownHostException", "java.lang.IllegalStateException"),
                UNRESOLVED_PEER.replace("Failed to resolve name", "Failed to update the load balancer"),
                UNRESOLVED_PEER.replace("io.grpc.internal.ManagedChannelImpl", "io.grpc.internal.OtherChannel"))) {
            DockerLogCaptureHelper.Audit audit = DockerLogCaptureHelper.audit(
                    line + LOOKUP_TRACE, expected, "DockerClass");
            assertEquals(java.util.List.of("undeclared container event with a stack trace: " + line),
                    audit.problems());
            assertEquals(line + LOOKUP_TRACE, audit.archivedOutput());
        }
        String atErrorLevel = UNRESOLVED_PEER.replace("WARN  ", "ERROR ");
        assertEquals(java.util.List.of("undeclared container " + atErrorLevel),
                DockerLogCaptureHelper.audit(atErrorLevel + LOOKUP_TRACE, expected, "DockerClass").problems());
    }

    @Test
    void classCaptureArchivesADeclaredWarningWithItsFlagAndReprintsItAtItsOwnLevel() throws Exception {
        Logger grpc = (Logger) LoggerFactory.getLogger("io.grpc.internal.ManagedChannelImpl");
        ListAppender<ILoggingEvent> reprinted = new ListAppender<>();
        reprinted.start();
        grpc.addAppender(reprinted);
        try {
            DockerLogCaptureHelper.beginClass("UnresolvedPeerDockerClass",
                    Set.of(IntentionalErrorFixture.RAFT_PEER_NAME_UNRESOLVED));
            DockerLogCaptureHelper.capture("unresolved-peer-container", "server1", UNRESOLVED_PEER + LOOKUP_TRACE);
            assertEquals(java.util.List.of(), DockerLogCaptureHelper.finishClass(directory));
        } finally {
            grpc.detachAppender(reprinted);
        }

        assertEquals(UNRESOLVED_PEER.replace("WARN  ", "WARN  *** INTENTIONAL ERROR: RAFT_PEER_NAME_UNRESOLVED, "
                        + "caused by UnresolvedPeerDockerClass *** ") + LOOKUP_TRACE,
                Files.readString(directory.resolve("UnresolvedPeerDockerClass/server1.log")));
        assertEquals(java.util.List.of(Level.WARN), reprinted.list.stream().map(ILoggingEvent::getLevel).toList());
    }

    @Test
    void everyDockerClassThatMakesAPeerUnreachableDeclaresThatItsNameMayNotResolve() throws Exception {
        int checked = 0;
        try (var sources = Files.list(Path.of("src/test/java/dev/mars/qraft/raft"))) {
            for (Path source : sources.filter(path ->
                    path.getFileName().toString().matches("Docker\\w+Test\\.java")).toList()) {
                Class<?> type = Class.forName("dev.mars.qraft.raft."
                        + source.getFileName().toString().replace(".java", ""), false, getClass().getClassLoader());
                ExpectedDockerErrorsHelper declared = type.getAnnotation(ExpectedDockerErrorsHelper.class);
                if (declared == null
                        || !Set.of(declared.value()).contains(IntentionalErrorFixture.RAFT_PEER_UNREACHABLE)) {
                    continue;
                }
                assertTrue(Set.of(declared.value()).contains(IntentionalErrorFixture.RAFT_PEER_NAME_UNRESOLVED),
                        type.getSimpleName() + " stops or disconnects a server, so a surviving server may fail to"
                                + " resolve that server's name");
                checked++;
            }
        }
        assertTrue(checked > 0, "no Docker class that makes a peer unreachable was found");
    }

    @Test
    void classCaptureArchivesOnlyTheUndrainedSuffixAndReprintsItsDeclaredError() throws Exception {
        String first = "2026-10-06 14:00:00.000 [main] INFO  example.Container - started\n";
        String error = "2026-10-06 14:00:01.000 [main] ERROR dev.mars.qraft.raft.RaftNode - Raft peer n2 became unreachable during AppendEntries\n";
        DockerLogCaptureHelper.beginClass("FirstDockerClass", Set.of());
        DockerLogCaptureHelper.capture("container-1", "server1", first);
        assertTrue(DockerLogCaptureHelper.finishClass(directory).isEmpty());

        DockerLogCaptureHelper.beginClass("SecondDockerClass", Set.of(IntentionalErrorFixture.RAFT_PEER_UNREACHABLE));
        DockerLogCaptureHelper.capture("container-1", "server1", first + error);
        assertTrue(DockerLogCaptureHelper.finishClass(directory).isEmpty());

        assertEquals(error.replace("ERROR ", "ERROR *** INTENTIONAL ERROR: RAFT_PEER_UNREACHABLE, "
                        + "caused by SecondDockerClass *** "),
                Files.readString(directory.resolve("SecondDockerClass/server1.log")));

        DockerLogCaptureHelper.beginClass("QuietDockerClass", Set.of());
        DockerLogCaptureHelper.capture("container-1", "server1", first + error);
        assertTrue(DockerLogCaptureHelper.finishClass(directory).isEmpty());
        assertEquals("", Files.readString(directory.resolve("QuietDockerClass/server1.log")));
    }
}
