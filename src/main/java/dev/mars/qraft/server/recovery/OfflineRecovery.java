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

package dev.mars.qraft.server.recovery;

import dev.mars.qraft.raft.ConfigurationCommand;
import dev.mars.qraft.raft.RaftConfiguration;
import dev.mars.qraft.raft.storage.RaftStorageFactory;
import dev.mars.qraft.raft.storage.ServerIdentity;
import dev.mars.qraft.state.ProtobufRaftCommandCodec;
import dev.mars.raftlog.storage.RaftStorage.LogEntryData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import static dev.mars.qraft.server.recovery.RecoveryExamination.await;
import static dev.mars.qraft.server.recovery.RecoveryExamination.describe;

/**
 * Offline lost-quorum recovery (membership Step 7, decision 8). It runs against stopped data directories only and
 * contacts no server.
 * <ul>
 *   <li>{@link #inspect} reports a directory without changing it;</li>
 *   <li>{@link #recover} copies a stopped survivor or backup into a new directory, appends one configuration entry
 *       making it the sole voter under a new server ID, and publishes that directory atomically. The source is
 *       never changed.</li>
 * </ul>
 * Ordinary server startup never reads a recovery input, claim or staging directory.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-10-22
 * @version 1.0
 */
public final class OfflineRecovery {
    private static final Logger logger = LoggerFactory.getLogger(OfflineRecovery.class);
    private static final ProtobufRaftCommandCodec CODEC = new ProtobufRaftCommandCodec();

    private OfflineRecovery() { }

    /**
     * Reports the stopped {@code directory} without changing it. It holds the directory's lock while it copies it
     * to a temporary directory, examines the copy, and deletes the copy; refused if the lock is held or the
     * directory has no readable server ID.
     */
    public static DirectoryReport inspect(Path directory) throws RecoveryRefusedException {
        Path source = directory.toAbsolutePath().normalize();
        requireDirectory(source);
        String serverId = serverIdOf(source);
        try (var _ = SourceLock.acquire(source)) {
            Path scratch = ownerOnlyTemporaryDirectory();
            try {
                Path copy = scratch.resolve("copy");
                DirectoryCopy.copy(source, copy);
                RecoveryExamination examined = RecoveryExamination.examine(copy, serverId);
                return new DirectoryReport(serverId, examined.metadata().currentTerm(),
                        examined.metadata().votedFor(), examined.lastLogIndex(), examined.lastLogTerm(),
                        examined.snapshotIndex(), examined.snapshotTerm(), examined.compactedThrough(),
                        examined.configuration(), examined.configurationSource(), examined.startupRefusal());
            } finally {
                DirectoryCopy.deleteTree(scratch);
            }
        } catch (IOException failure) {
            throw new RecoveryRefusedException("Inspecting " + source + " failed: " + describe(failure), failure);
        }
    }

    /**
     * Performs {@code plan}, or refuses it with the source unchanged (Step 7 contract). The checks that only read
     * come first, so a plan they refuse makes no claim. Then, holding the source's lock, it claims the source,
     * copies it into a staging directory beside the output, examines the copy with the ordinary startup checks,
     * appends the recovery entry and sets the term, gives the copy its new server ID, and publishes it by renaming
     * the staging directory to the output. The claim records each durable step, so an interrupted run either
     * completes on the next run or is refused without change.
     */
    public static RecoveryResult recover(RecoveryPlan plan) throws RecoveryRefusedException {
        return recover(plan, _ -> { });
    }

    /** As {@link #recover(RecoveryPlan)}, telling {@code checkpoints} of each durable step as it completes. */
    static RecoveryResult recover(RecoveryPlan plan, Consumer<RecoveryCheckpoint> checkpoints)
            throws RecoveryRefusedException {
        Path source = plan.source().toAbsolutePath().normalize();
        Path output = plan.output().toAbsolutePath().normalize();
        if (output.startsWith(source) || source.startsWith(output)) {
            throw new RecoveryRefusedException("The output " + output + " and the source " + source
                    + " overlap; recovery publishes a separate directory and never writes inside the source");
        }
        requireDirectory(source);
        String sourceServerId = serverIdOf(source);
        if (!sourceServerId.equals(plan.sourceServerId())) {
            throw new RecoveryRefusedException("The source " + source + " holds server ID " + sourceServerId
                    + ", which does not match the input's " + plan.sourceServerId());
        }
        try {
            Optional<RecoveryClaim> claim = RecoveryClaim.read(source);
            if (claim.isPresent()) {
                Optional<RecoveryResult> recorded = completedEarlier(claim.get(), output);
                if (recorded.isPresent()) return recorded.get();
            } else if (Files.exists(output)) {
                throw new RecoveryRefusedException("The output " + output
                        + " already exists; recovery publishes a new directory and never replaces one");
            }
            try (var _ = SourceLock.acquire(source)) {
                RecoveryClaim claimed = claim.orElseGet(() -> RecoveryClaim.claimed(source, sourceServerId, output));
                boolean madeHere = claim.isEmpty();
                if (madeHere) claimed.create();
                checkpoints.accept(RecoveryCheckpoint.CLAIM_CREATED);
                return publish(plan, source, output, claimed, madeHere, checkpoints);
            }
        } catch (IOException failure) {
            throw new RecoveryRefusedException("Recovering " + source + " into " + output + " failed: "
                    + describe(failure) + ". The source is unchanged", failure);
        }
    }

    /**
     * Applies an existing claim: refused if it is for another output or already published; the claimed result if
     * an interrupted run published the output but did not record it; empty if the run should go on.
     */
    private static Optional<RecoveryResult> completedEarlier(RecoveryClaim claim, Path output)
            throws RecoveryRefusedException, IOException {
        if (!claim.output().equals(output)) {
            throw new RecoveryRefusedException("The source " + claim.source() + " is already claimed by the "
                    + "recovery into " + claim.output() + "; one source never seeds two clusters");
        }
        if (claim.published()) {
            throw new RecoveryRefusedException("The source " + claim.source() + " was already recovered into "
                    + output + "; start the recovered server there, and keep the source as evidence");
        }
        if (!Files.exists(output)) return Optional.empty();
        Optional<String> published = ServerIdentity.existing(output);
        if (claim.newServerId().isEmpty() || !claim.newServerId().equals(published)) {
            throw new RecoveryRefusedException("The output " + output + " already exists, and its server ID is not "
                    + "the one the recovery claim beside " + claim.source() + " records");
        }
        claim.publishedClaim().replace();
        logger.info("Recorded the publication of {} under server ID {}, which an earlier run completed",
                output, published.get());
        return Optional.of(new RecoveryResult(published.get(), claim.recoveryIndex(), claim.recoveryTerm(), output,
                RecoveryResult.Outcome.PUBLICATION_RECORDED));
    }

    /**
     * Copies, examines and publishes. A source the examination refuses seeds nothing, so a refusal before the claim
     * records a new server ID releases the claim this run made ({@code madeHere}); a claim from an earlier run is
     * left as it was.
     */
    private static RecoveryResult publish(RecoveryPlan plan, Path source, Path output, RecoveryClaim claimed,
                                          boolean madeHere, Consumer<RecoveryCheckpoint> checkpoints)
            throws RecoveryRefusedException, IOException {
        Path staging = output.resolveSibling("." + output.getFileName() + ".qraft-recovering");
        Files.createDirectories(output.getParent());
        DirectoryCopy.deleteTree(staging); // only ever an unpublished copy from an interrupted run
        boolean recorded = false;
        try {
            DirectoryCopy.copy(source, staging);
            checkpoints.accept(RecoveryCheckpoint.SOURCE_COPIED);
            RecoveryExamination examined = RecoveryExamination.examine(staging, claimed.sourceServerId());
            checkpoints.accept(RecoveryCheckpoint.EXAMINED);
            if (examined.startupRefusal().isPresent()) {
                throw new RecoveryRefusedException("The source " + source + " would be refused by ordinary "
                        + "startup: " + examined.startupRefusal().get());
            }
            RaftConfiguration inForce = examined.configuration().orElseThrow();
            if (inForce.server(claimed.sourceServerId()).isEmpty()) {
                throw new RecoveryRefusedException("Server " + claimed.sourceServerId() + " is not a member of its "
                        + "own configuration in force; a removed server cannot seed a cluster");
            }
            String newServerId = newServerId(examined.retainedServerIds());
            long index = examined.lastLogIndex() + 1;
            long term = Math.max(examined.metadata().currentTerm(), examined.lastLogTerm()) + 1;
            appendRecoveryEntry(staging, index, term, new RaftConfiguration(List.of(
                    new RaftConfiguration.Server(newServerId, plan.name(), plan.address(), true))), checkpoints);
            checkpoints.accept(RecoveryCheckpoint.METADATA_UPDATED);
            ServerIdentity.replace(staging, newServerId);
            checkpoints.accept(RecoveryCheckpoint.SERVER_ID_REPLACED);
            RecoveryClaim prepared = claimed.prepared(newServerId, index, term);
            prepared.replace();
            recorded = true;
            checkpoints.accept(RecoveryCheckpoint.CLAIM_PREPARED);
            Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE);
            ServerIdentity.forceDirectory(output.getParent());
            checkpoints.accept(RecoveryCheckpoint.PUBLISHED);
            prepared.publishedClaim().replace();
            checkpoints.accept(RecoveryCheckpoint.CLAIM_PUBLISHED);
            logger.info("Recovered {} into {}: sole voter {} ({}) at index {} term {}", source, output,
                    plan.name(), newServerId, index, term);
            return new RecoveryResult(newServerId, index, term, output, RecoveryResult.Outcome.RECOVERED);
        } catch (RecoveryRefusedException refused) {
            DirectoryCopy.deleteTree(staging);
            if (madeHere && !recorded) claimed.release();
            throw refused;
        } catch (IOException | RuntimeException failure) {
            DirectoryCopy.deleteTree(staging);
            throw failure;
        }
    }

    private static void appendRecoveryEntry(Path staging, long index, long term, RaftConfiguration configuration,
                                            Consumer<RecoveryCheckpoint> checkpoints)
            throws RecoveryRefusedException {
        try {
            RaftStorageFactory.DurableStorage storage = await(RaftStorageFactory.createDurable(staging, true));
            try {
                // RaftLog writes only once it knows its tail; the examination stopped with the tail it reported.
                List<LogEntryData> replayed = await(storage.wal().replayLog());
                long tail = replayed.isEmpty() ? await(storage.wal().compactionBoundary()) : replayed.getLast().index();
                if (tail != index - 1) {
                    throw new RecoveryRefusedException("The copy's log ends at index " + tail
                            + ", not at the examined index " + (index - 1) + "; nothing was written");
                }
                await(storage.wal().appendEntries(List.of(new LogEntryData(index, term,
                        CODEC.serialize(new ConfigurationCommand(configuration))))));
                await(storage.wal().sync());
                checkpoints.accept(RecoveryCheckpoint.RECOVERY_ENTRY_SYNCED);
                await(storage.wal().updateMetadata(term, Optional.empty()));
            } finally {
                await(storage.snapshots().closeAsync());
                await(storage.wal().closeAsync());
            }
        } catch (ExecutionException failure) {
            throw new RecoveryRefusedException("Writing the recovery entry failed: " + describe(failure.getCause()),
                    failure.getCause());
        }
    }

    /** A new server ID that no retained configuration names. */
    private static String newServerId(Set<String> retained) {
        String id = UUID.randomUUID().toString();
        while (retained.contains(id)) id = UUID.randomUUID().toString();
        return id;
    }

    /** A temporary directory for inspect's copy, which holds catalog data, readable only by its owner on POSIX. */
    private static Path ownerOnlyTemporaryDirectory() throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return Files.createTempDirectory("qraft-inspect-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
        return Files.createTempDirectory("qraft-inspect-");
    }

    private static void requireDirectory(Path directory) throws RecoveryRefusedException {
        if (!Files.isDirectory(directory)) {
            throw new RecoveryRefusedException(directory + " is not a directory");
        }
    }

    /** The directory's server ID; refused if it has none or it cannot be read, since then its history is nobody's. */
    private static String serverIdOf(Path directory) throws RecoveryRefusedException {
        try {
            return ServerIdentity.existing(directory).orElseThrow(() -> new RecoveryRefusedException(directory
                    + " has no server-id file, so whose history it holds cannot be established"));
        } catch (IllegalStateException unreadable) {
            throw new RecoveryRefusedException(unreadable.getMessage(), unreadable);
        } catch (IOException unreadable) {
            throw new RecoveryRefusedException("The server ID in " + directory + " cannot be read: "
                    + unreadable.getMessage(), unreadable);
        }
    }
}
