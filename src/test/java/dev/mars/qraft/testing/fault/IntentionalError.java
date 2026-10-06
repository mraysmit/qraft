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
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The complete list of errors the test suite causes on purpose. Every error a test causes deliberately has an
 * entry here, and nowhere else.
 *
 * <ul>
 * <li>An {@link Kind#INJECTED_FAILURE} is a failure a test injects into production code by throwing an
 * {@link InjectedFault} that names the entry. A logged event is that failure when its exception, or any exception
 * in its cause or suppressed chain, is that {@link InjectedFault}.</li>
 * <li>An {@link Kind#INTENTIONAL_ERROR} is an error that production code logs because a test arranged the
 * situation, with no injected exception: a partitioned peer, a corrupted file, a refused configuration. It names
 * the exact logger, level, and complete message pattern, and labels an event only during a test that declares it
 * with {@link IntentionalErrors#expect(IntentionalError)}.</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-04
 * @version 1.0
 */
public enum IntentionalError {

    /** Used only by the tests of this package, which test the labelling and checks themselves. */
    SELF_TEST_INJECTED_FAILURE,

    /** A test transport's message handler fails while a delayed request is being delivered. */
    TRANSPORT_HANDLER_FAILURE,

    /** A shutdown hook throws or completes with a failure supplied by its test. */
    SHUTDOWN_HOOK_FAILURE,

    /** A test storage implementation refuses a Raft metadata update. */
    RAFT_METADATA_PERSISTENCE_FAILURE,

    /** A test state machine refuses to apply a committed command. */
    RAFT_STATE_MACHINE_APPLY_FAILURE,

    /** A test transport fails during start or while serving an RPC. */
    RAFT_TRANSPORT_FAILURE,

    /** A test makes Raft transport or storage resource shutdown fail. */
    RAFT_RESOURCE_SHUTDOWN_FAILURE,

    /** A test storage fails a WAL mutation after or during its durable transition. */
    RAFT_WAL_TRANSITION_FAILURE,

    /** A test snapshot store or state machine fails snapshot publication, restoration, or compaction. */
    RAFT_SNAPSHOT_OPERATION_FAILURE,

    /** A test storage fails recovery while an installed snapshot is being reconciled with the WAL. */
    RAFT_RECOVERY_STORAGE_FAILURE,

    /** Bootstrap refuses a peer whose advertised server list disagrees with the local list. */
    BOOTSTRAP_SERVER_LISTS_DISAGREE("dev.mars.qraft.controller.raft.ClusterBootstrap", Level.ERROR,
            "Bootstrap refused: \\S+ answers as \\S+ and lists \\[.*], but this server lists \\[.*]"),

    /** A second WAL writer is deliberately opened against a directory whose lock is already held. */
    WAL_DIRECTORY_ALREADY_LOCKED("dev.mars.raftlog.storage.FileRaftStorage", Level.ERROR,
            "Cannot acquire exclusive lock at .*[\\\\/]raft\\.lock: lock already held in this JVM"),

    /** A test corrupts a complete, acknowledged WAL record and verifies that replay fences the storage. */
    WAL_AMBIGUOUS_CORRUPTION("dev.mars.raftlog.storage.FileRaftStorage", Level.ERROR,
            "WAL contains ambiguous corruption: WAL .*[\\\\/]raft\\.log is corrupt at byte \\d+ of \\d+;"
                    + " \\d+ entries precede the damage\\. Not repaired: restore this node from its peers\\."
                    + " Storage instance is now fenced; close it and open a fresh instance"),

    /** A best-effort hook is deliberately left incomplete past its test's shutdown deadline. */
    BEST_EFFORT_SHUTDOWN_HOOK_TIMEOUT("dev.mars.qraft.controller.lifecycle.ShutdownCoordinator", Level.ERROR,
            "Best-effort shutdown hook 'slow' timed out after 50 ms"),

    /** A critical hook is deliberately left incomplete, producing its hook and overall-shutdown errors. */
    CRITICAL_SHUTDOWN_HOOK_TIMEOUT("dev.mars.qraft.controller.lifecycle.ShutdownCoordinator", Level.ERROR,
            "(?:Critical shutdown hook 'node-stop' timed out after 50 ms"
                    + "|Shutdown failed before resources could be closed safely: null)"),

    /** Recovery refuses legacy Raft state that has no recorded cluster configuration. */
    RAFT_STATE_WITHOUT_CONFIGURATION("dev.mars.qraft.controller.raft.RaftNode", Level.ERROR,
            "Failed to recover Raft state from storage: Node \\S+ holds Raft state but no cluster configuration:"
                    + " the data predates configurations in the log and is not upgraded; start it on an empty"
                    + " data directory"),

    /** A follower refuses an append that would overwrite an entry it has already committed. */
    COMMITTED_ENTRY_REPLACEMENT("dev.mars.qraft.controller.raft.RaftNode", Level.ERROR,
            "Refusing AppendEntries from leader \\S+: it would replace the committed entry at index \\d+"
                    + " \\(commit index \\d+\\)"),

    /** A stopped or partitioned Raft peer is deliberately made unreachable. */
    RAFT_PEER_UNREACHABLE("dev.mars.qraft.controller.raft.RaftNode", Level.ERROR,
            "(?:Raft peer \\S+ became unreachable during AppendEntries|Failed to retrieve vote from \\S+)"),

    /** A test deliberately fences the transition sequencer and then exercises a rejected operation. */
    RAFT_FENCED_OPERATION("dev.mars.qraft.controller.raft.RaftNode", Level.ERROR,
            "(?:Failed to persist command to WAL|AppendEntries failed during durable transition"
                    + "|Failed to establish leadership no-op for term \\d+):"
                    + " Raft transition sequencer is fenced"),

    /** A test fills the bounded transition queue and verifies that client admission is rejected. */
    RAFT_TRANSITION_QUEUE_FULL("dev.mars.qraft.controller.raft.RaftNode", Level.ERROR,
            "Failed to persist command to WAL: Raft transition queue capacity \\d+ has been reached"),

    /** Startup propagates a deliberately corrupted WAL failure through both recovery log sites. */
    RAFT_RECOVERY_AMBIGUOUS_CORRUPTION("dev.mars.qraft.controller.raft.RaftNode", Level.ERROR,
            "(?:Recovery failed|Failed to recover Raft state from storage): WAL .* is corrupt at byte \\d+ of \\d+;"
                    + " \\d+ entries precede the damage\\. Not repaired: restore this node from its peers\\."),

    /** Stopping during recovery deliberately makes already-scheduled startup work encounter the drain. */
    RAFT_STARTUP_DRAINING("dev.mars.qraft.controller.raft.RaftNode", Level.ERROR,
            "Failed to (?:establish leadership no-op for term \\d+|recover Raft state from storage):"
                    + " Raft transition sequencer is draining"),

    /** Used only by the tests of this package, which test the labelling and checks themselves. */
    SELF_TEST_INTENTIONAL_ERROR("dev.mars.qraft.testing.fault.selftest", Level.ERROR,
            "Self-test intentional error \\d+");

    /** Whether a test injects the failure or arranges the situation that produces the error. */
    public enum Kind {
        INJECTED_FAILURE("INJECTED FAILURE", "injected by"),
        INTENTIONAL_ERROR("INTENTIONAL ERROR", "caused by");

        private final String title;
        private final String attribution;

        Kind(String title, String attribution) {
            this.title = title;
            this.attribution = attribution;
        }

        /** The label's opening words, for example {@code INJECTED FAILURE}. */
        public String title() {
            return title;
        }

        /** How the label names the test, for example {@code injected by}. */
        public String attribution() {
            return attribution;
        }
    }

    private final Kind kind;
    private final String loggerName;
    private final Level level;
    private final Pattern message;

    IntentionalError() {
        this.kind = Kind.INJECTED_FAILURE;
        this.loggerName = null;
        this.level = null;
        this.message = null;
    }

    IntentionalError(String loggerName, Level level, String messagePattern) {
        this.kind = Kind.INTENTIONAL_ERROR;
        this.loggerName = Objects.requireNonNull(loggerName, "loggerName");
        this.level = Objects.requireNonNull(level, "level");
        this.message = Pattern.compile(Objects.requireNonNull(messagePattern, "messagePattern"));
    }

    public Kind kind() {
        return kind;
    }

    /**
     * Whether {@code event} is this intentional error: the same logger and level, and a formatted message that the
     * pattern matches completely. Always false for an injected failure, which is recognised by its exception.
     */
    boolean matches(ILoggingEvent event) {
        return kind == Kind.INTENTIONAL_ERROR
                && loggerName.equals(event.getLoggerName())
                && level.equals(event.getLevel())
                && message.matcher(event.getFormattedMessage()).matches();
    }
}
