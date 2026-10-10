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
import dev.mars.qraft.server.QraftServerService;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Test fault-definition fixture listing the errors the test suite causes on purpose.
 * Every error a test causes deliberately has an
 * entry here, and nowhere else.
 *
 * <ul>
 * <li>An {@link Kind#INJECTED_FAILURE} is a failure a test injects into production code by throwing an
 * {@link InjectedFaultFixture} that names the entry. A logged event is that failure when its exception, or any exception
 * in its cause or suppressed chain, is that {@link InjectedFaultFixture}.</li>
 * <li>An {@link Kind#INTENTIONAL_ERROR} is an error that production code logs because a test arranged the
 * situation, with no injected exception: a partitioned peer, a corrupted file, a refused configuration. It names
 * the exact logger, level, and complete message pattern, and labels an event only during a test that declares it
 * with {@link IntentionalErrorsHelper#expect(IntentionalErrorFixture)}. A declared uncaught rethrow signature
 * also requires the exact failure to have been logged earlier in the same container output.</li>
 * </ul>
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-04
 * @version 1.0
 */
public enum IntentionalErrorFixture {

    /** Used only by the tests of this package, which test the labelling and checks themselves. */
    SELF_TEST_INJECTED_FAILURE,

    /** A test transport's message handler fails while a delayed request is being delivered. */
    TRANSPORT_HANDLER_FAILURE,

    /** Test teardown interrupts a delayed in-memory delivery that is waiting for its target node. */
    TRANSPORT_DELIVERY_INTERRUPTED,

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
    BOOTSTRAP_SERVER_LISTS_DISAGREE("dev.mars.qraft.raft.ClusterBootstrap", Level.ERROR,
            "Bootstrap refused: \\S+ answers as \\S+ and lists \\[.*], but this server lists \\[.*]"),

    /** A second WAL writer is deliberately opened against a directory whose lock is already held. */
    WAL_DIRECTORY_ALREADY_LOCKED("dev.mars.raftlog.storage.FileRaftStorage", Level.ERROR,
            "Cannot acquire exclusive lock at .*[\\\\/]raft\\.lock:"
                    + " (?:lock already held in this JVM|another process holds the lock)"),

    /** A test corrupts a complete, acknowledged WAL record and verifies that replay fences the storage. */
    WAL_AMBIGUOUS_CORRUPTION("dev.mars.raftlog.storage.FileRaftStorage", Level.ERROR,
            "WAL contains ambiguous corruption: WAL .*[\\\\/]raft\\.log is corrupt at byte \\d+ of \\d+;"
                    + " \\d+ entries precede the damage\\. Not repaired: restore this node from its peers\\."
                    + " Storage instance is now fenced; close it and open a fresh instance"),

    /** A best-effort hook is deliberately left incomplete past its test's shutdown deadline. */
    BEST_EFFORT_SHUTDOWN_HOOK_TIMEOUT("dev.mars.qraft.server.lifecycle.ShutdownCoordinator", Level.ERROR,
            "Best-effort shutdown hook 'slow' timed out after 50 ms"),

    /** A critical hook is deliberately left incomplete, producing its hook and overall-shutdown errors. */
    CRITICAL_SHUTDOWN_HOOK_TIMEOUT("dev.mars.qraft.server.lifecycle.ShutdownCoordinator", Level.ERROR,
            "(?:Critical shutdown hook 'node-stop' timed out after 50 ms"
                    + "|Shutdown failed before resources could be closed safely: null)"),

    /** Recovery refuses legacy Raft state that has no recorded cluster configuration. */
    RAFT_STATE_WITHOUT_CONFIGURATION("dev.mars.qraft.raft.RaftNode", Level.ERROR,
            "Failed to recover Raft state from storage: Node \\S+ holds Raft state but no cluster configuration:"
                    + " the data predates configurations in the log and is not upgraded; start it on an empty"
                    + " data directory"),

    /** Recovery refuses a log that is compacted further than the published snapshot reaches. */
    RAFT_RECOVERY_SNAPSHOT_MISSING("dev.mars.qraft.raft.RaftNode", Level.ERROR,
            "(?:Recovery failed|Failed to recover Raft state from storage): Node \\S+ cannot recover: its log is"
                    + " compacted through index \\d+ but its published snapshot reaches only index \\d+;"
                    + " a published snapshot is missing or older than the log\\. Preserve the storage directory"
                    + " for diagnosis and restore this node from its peers"),

    /** A follower refuses an append that would overwrite an entry it has already committed. */
    COMMITTED_ENTRY_REPLACEMENT("dev.mars.qraft.raft.RaftNode", Level.ERROR,
            "Refusing AppendEntries from leader \\S+: it would replace the committed entry at index \\d+"
                    + " \\(commit index \\d+\\)"),

    /** A stopped or partitioned Raft peer is deliberately made unreachable. */
    RAFT_PEER_UNREACHABLE("dev.mars.qraft.raft.RaftNode", Level.ERROR,
            "(?:Raft peer \\S+ became unreachable during AppendEntries|Failed to retrieve vote from \\S+)"),

    /**
     * A stopped or disconnected Docker peer's name no longer resolves. gRPC reports each failed lookup as a
     * warning whose message holds the lookup's stack trace.
     */
    RAFT_PEER_NAME_UNRESOLVED("io.grpc.internal.ManagedChannelImpl", Level.WARN,
            "\\[Channel<\\d+>: \\((?<host>[^:)]+):\\d+\\)] Failed to resolve name\\."
                    + " status=Status\\{code=UNAVAILABLE, description=Unable to resolve host \\k<host>,"
                    + " cause=java\\.lang\\.RuntimeException: java\\.net\\.UnknownHostException: \\k<host>(?:: .+)?"),

    /** A stopped Docker peer interrupts an in-progress snapshot transfer or its completion callback. */
    RAFT_SNAPSHOT_TRANSFER_INTERRUPTED("dev.mars.qraft.raft.RaftNode", Level.ERROR,
            "(?:Failed to send InstallSnapshot chunk \\d+/\\d+ to \\S+: UNAVAILABLE:"
                    + " (?:io exception|Unable to resolve host \\S+)"
                    + "|Failed to process InstallSnapshot response from \\S+:"
                    + " Raft transition sequencer is draining)"),

    /** A test deliberately fences the transition sequencer and then exercises a rejected operation. */
    RAFT_FENCED_OPERATION("dev.mars.qraft.raft.RaftNode", Level.ERROR,
            "(?:Failed to persist command to WAL|AppendEntries failed during durable transition"
                    + "|Failed to establish leadership no-op for term \\d+):"
                    + " Raft transition sequencer is fenced"),

    /** A test fills the bounded transition queue and verifies that client admission is rejected. */
    RAFT_TRANSITION_QUEUE_FULL("dev.mars.qraft.raft.RaftNode", Level.ERROR,
            "Failed to persist command to WAL: Raft transition queue capacity \\d+ has been reached"),

    /** Startup propagates a deliberately corrupted WAL failure through both recovery log sites. */
    RAFT_RECOVERY_AMBIGUOUS_CORRUPTION("dev.mars.qraft.raft.RaftNode", Level.ERROR,
            "(?:Recovery failed|Failed to recover Raft state from storage): WAL .* is corrupt at byte \\d+ of \\d+;"
                    + " \\d+ entries precede the damage\\. Not repaired: restore this node from its peers\\."),

    /** Server startup reports that deliberately corrupted storage left the node live but fenced. */
    SERVER_RECOVERY_AMBIGUOUS_CORRUPTION(QraftServerService.class.getName(), Level.ERROR,
            "Raft recovery failed; node remains live but unready and will not participate\\."
                    + " Preserve the node directory for diagnosis, then replace it from a healthy peer and restart:"
                    + " WAL .* is corrupt at byte \\d+ of \\d+; \\d+ entries precede the damage\\."
                    + " Not repaired: restore this node from its peers\\."),

    /** Server startup logs a deliberate cross-process WAL lock conflict, then rethrows it uncaught. */
    SERVER_STORAGE_ALREADY_LOCKED(QraftServerService.class.getName(), Level.ERROR,
            "Failed to initialize Raft storage: (?<failure>Cannot acquire exclusive lock on WAL directory: .*\\."
                    + " Another process may be using this storage\\.)",
            "java\\.util\\.concurrent\\.CompletionException:"
                    + " dev\\.mars\\.raftlog\\.storage\\.FileRaftStorage\\$StorageException:"
                    + " (?<failure>Cannot acquire exclusive lock on WAL directory: .*\\."
                    + " Another process may be using this storage\\.)"),

    /** Stopping during recovery deliberately makes already-scheduled startup work encounter the drain. */
    RAFT_STARTUP_DRAINING("dev.mars.qraft.raft.RaftNode", Level.ERROR,
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
    private final Pattern uncaughtException;

    IntentionalErrorFixture() {
        this.kind = Kind.INJECTED_FAILURE;
        this.loggerName = null;
        this.level = null;
        this.message = null;
        this.uncaughtException = null;
    }

    IntentionalErrorFixture(String loggerName, Level level, String messagePattern) {
        this(loggerName, level, messagePattern, null);
    }

    IntentionalErrorFixture(String loggerName, Level level, String messagePattern, String uncaughtExceptionPattern) {
        this.kind = Kind.INTENTIONAL_ERROR;
        this.loggerName = Objects.requireNonNull(loggerName, "loggerName");
        this.level = Objects.requireNonNull(level, "level");
        this.message = Pattern.compile(Objects.requireNonNull(messagePattern, "messagePattern"));
        this.uncaughtException = uncaughtExceptionPattern == null ? null : Pattern.compile(uncaughtExceptionPattern);
    }

    public Kind kind() {
        return kind;
    }

    /** The level an intentional error is logged at, or {@code null} for an injected failure. */
    Level level() {
        return level;
    }

    /**
     * Whether {@code event} is this intentional error: the same logger and level, and a formatted message that the
     * pattern matches completely. Always false for an injected failure, which is recognised by its exception.
     */
    boolean matches(ILoggingEvent event) {
        return matches(event.getLoggerName(), event.getLevel(), event.getFormattedMessage());
    }

    /** Whether an externally captured log event has this intentional error's exact signature. */
    boolean matches(String eventLogger, Level eventLevel, String eventMessage) {
        return kind == Kind.INTENTIONAL_ERROR
                && loggerName.equals(eventLogger)
                && level.equals(eventLevel)
                && message.matcher(eventMessage).matches();
    }

    /** Matches an uncaught rethrow only when its exact failure was already reported by this intentional error. */
    boolean matchesUncaughtException(String exceptionDescription, String reportedMessage) {
        if (uncaughtException == null) return false;
        var exceptionMatch = uncaughtException.matcher(exceptionDescription);
        var reportedMatch = message.matcher(reportedMessage);
        return exceptionMatch.matches() && reportedMatch.matches()
                && exceptionMatch.group("failure").equals(reportedMatch.group("failure"));
    }
}
