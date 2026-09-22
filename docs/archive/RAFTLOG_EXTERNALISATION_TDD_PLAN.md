# RaftLog Externalisation Plan

**Archive status:** Closed and archived on 2026-09-22.  
**Status:** Complete. Core migration implemented 2026-09-12; Tranche 8 system and
failure verification completed 2026-09-22  
**Last updated:** 2026-09-22  
**Primary objective:** Make RaftLog the sole implementation of Raft WAL behavior
used by Qraft, while Qraft retains consensus, state-machine, and application
snapshot responsibilities.

### Migration checkpoint

Completed on 2026-09-12:

- Upgraded the consumed RaftLog artifact from 1.1.0 to 1.3.0.
- Added the `SnapshotStore` API and a durable, checksummed, atomically published
  file implementation with legacy snapshot decoding.
- Proved snapshot persistence and RaftLog prefix compaction across close/reopen.
- Changed `RaftNode` to call RaftLog's `RaftStorage` directly and to call
  `SnapshotStore` separately.
- Changed production construction and service startup to bypass the combined
  storage adapter.
- Proved node recovery from a snapshot plus a post-snapshot entry using the real
  external file WAL.
- Replaced the old test storage with a purpose-built test fixture implementing
  the external interface; no compatibility bridge remains.
- Deleted the duplicate storage SPI, internal file WAL, database backend,
  semantic adapter, backend selector, and obsolete backend tests.
- Removed the unused database dependency and restricted configuration to the
  external WAL with synchronous durability enabled.
- Added a real-library contract for metadata, suffix replacement, prefix
  compaction, reopen, locking, corruption, and the legacy on-disk format.
- Delegated follower conflict calculation to RaftLog `AppendPlan`. A red test
  exposed and then fixed loss of a matching-term entry after an earlier conflict.
- Passed a clean full-reactor run: 435 tests, zero failures, zero errors.

### Verification checkpoint

Completed on 2026-09-22:

- Tranche 8 system-level failure and container matrix implemented; see the
  Tranche 8 disposition below and the task list in
  [`task-list-22-sep-2026.md`](task-list-22-sep-2026.md).
- Operator runbook added: [`RAFT_STORAGE_OPERATIONS.md`](../RAFT_STORAGE_OPERATIONS.md).
- Full reactor green: controller 307 tests including 8 Docker acceptance tests,
  core 231, distributed-state 14, agent 13, runtime 13, tenant 5; zero failures,
  errors, or skips. Five-node `NetworkPartitionTest` re-enabled and passing.

No remaining work is tracked under this plan.

## 1. Outcome

After this migration, Qraft will not implement term/vote persistence, WAL record
encoding, append durability, suffix truncation, WAL replay, corruption recovery,
directory locking, fencing, or physical prefix compaction.

Those operations will be delegated to the published RaftLog interface and
implementation. Qraft will own a separate durable application snapshot store and
will coordinate it with RaftLog prefix compaction.

The migration will follow strict red-green-refactor cycles. No duplicated
production implementation is removed until real-storage tests prove the replacement
and existing on-disk data has an explicit compatibility path.

## 2. Current-state findings

### 2.1 Duplicated production surface

Qraft currently contains:

- `dev.mars.qraft.controller.raft.storage.RaftStorage`, a duplicate storage SPI.
- `storage.file.FileRaftStorage`, an independent file WAL.
- `storage.rocksdb.RocksDbRaftStorage`, an independent database-backed Raft store.
- `InMemoryRaftStorage`, a production-source implementation used mainly by tests.
- `RaftLogStorageAdapter`, which translates from RaftLog to the duplicate SPI.
- `RaftStorageFactory`, which selects among all four implementations.

The duplicated Qraft SPI includes RaftLog operations plus Qraft-specific snapshot
operations. That combined interface obscures which component owns each guarantee.

### 2.2 Current RaftLog adapter defects

- Qraft pins RaftLog `1.1.0`; the checked sister project is `1.3.0`.
- `saveSnapshot` stores bytes only in adapter memory.
- `loadSnapshot` cannot recover a snapshot after process restart.
- `truncatePrefix` completes successfully without invoking RaftLog.
- The adapter owns an unused `JavaRuntime` reference.
- Its documentation refers to an obsolete runtime integration model.
- It converts every call through a custom future type, increasing contract surface.

### 2.3 Unsafe semantic drift

Qraft's internal file backend and RaftLog use apparently matching version-one WAL
constants and layouts:

- `meta.dat` for term and vote.
- `raft.log` for framed `APPEND` and `TRUNCATE` records.
- Magic value `0x52414654`.
- Record version `1`.
- The same header fields and CRC32C framing.

This apparent compatibility is not yet a guarantee. It must be demonstrated with
immutable cross-reader fixtures before the internal backend is removed.

Their recovery behavior is not equivalent. The internal backend may stop replay
and truncate after broadly classified tail corruption. Current RaftLog repairs
only structurally incomplete EOF writes; complete or ambiguous corruption fails
and fences the instance. Qraft must adopt the stricter behavior.

### 2.4 Configuration inconsistencies

- The packaged properties select `raftlog` by default.
- Java fallback paths and factory documentation still describe `file` as default.
- `file`, `rocksdb`, `memory`, `wal`, and other aliases expose multiple production
  interpretations of the same Raft safety contract.
- Automatic snapshots are enabled by default even though the production adapter
  does not persist them across restart or physically compact the WAL.

### 2.5 Test coupling

Storage types appear across Raft node, snapshot, adapter, contract, and backend
tests. Several failure-injection tests implement the duplicate Qraft SPI directly.
The migration needs shared purpose-built fixtures against the external interface,
not a large one-step signature replacement.

## 3. Responsibility boundary

### 3.1 RaftLog owns

- Opening and exclusively locking a WAL directory.
- Atomically persisting `currentTerm` and `votedFor`.
- Appending indexed, termed, opaque payloads.
- Persisting suffix-truncation records.
- The explicit `sync` durability barrier.
- Sequential logical replay.
- Torn-tail repair under the documented narrow conditions.
- Detection and fencing for ambiguous corruption or durability failure.
- Durable physical prefix compaction when requested.
- Closing its files, lock, and owned executor.

### 3.2 Qraft owns

- Elections, roles, RPC validation, replication, and quorum logic.
- Allocation and validation of Raft indexes and terms.
- Commit and applied indexes.
- Application command encoding and decoding.
- In-memory logical log and snapshot-boundary sentinel.
- State-machine execution and materialized queries.
- Snapshot byte creation and restoration.
- Durable snapshot publication through `SnapshotStore`.
- Snapshot transfer through `InstallSnapshot`.
- Deciding when a durable snapshot permits WAL prefix compaction.
- Node lifecycle and operational reporting.

### 3.3 Shared interaction rule

The required sequence is always:

```text
prepare without mutation
    -> persist through RaftLog
    -> cross the documented durability barrier
    -> mutate Qraft memory
    -> respond or continue replication
```

No adapter may report success without performing the delegated operation.

## 4. Target architecture

```text
RaftNode
|-- dev.mars.raftlog.storage.RaftStorage
|   |-- metadata
|   |-- append / truncate suffix / sync
|   |-- replay
|   `-- truncate prefix
|-- SnapshotStore
|   |-- save atomically
|   `-- load latest
|-- RaftLog AppendPlan
`-- RaftLogApplicator
    |-- apply committed command
    |-- take application snapshot
    `-- restore application snapshot
```

The preferred end state is for `RaftNode` to depend directly on RaftLog's
`RaftStorage`. If temporary future conversion is necessary, conversion occurs in
small private helpers and does not recreate a second storage interface.

`SnapshotStore` is a Qraft interface because application snapshots are outside
RaftLog's scope:

```java
public interface SnapshotStore extends AutoCloseable {
    CompletableFuture<Void> open(Path directory);
    CompletableFuture<Void> saveAtomically(SnapshotData snapshot);
    CompletableFuture<Optional<SnapshotData>> loadLatest();
    void close();
}
```

`SnapshotData` contains format version, included index, included term, payload,
and checksum. Arrays are defensively copied.

## 5. Strict TDD rules

Every behavioral change follows this loop:

1. **Red:** Add one focused failing test expressing an externally visible safety
   or compatibility requirement.
2. **Confirm red:** Run only that test and verify it fails for the intended reason.
3. **Green:** Make the smallest production change that satisfies the test.
4. **Focused verification:** Run the affected test class and storage contract.
5. **Refactor:** Remove duplication or improve names without changing behavior.
6. **Regression verification:** Run the controller module and then the full reactor.

Additional rules:

- Never write production code before observing the corresponding test fail.
- Use the real RaftLog file implementation for durability and compatibility tests.
- Use purpose-built recording or failure-injection fakes only for call ordering and
  unreachable filesystem failure points.
- Do not use a mocking framework.
- Do not weaken assertions to accommodate existing behavior.
- Keep each migration step buildable and independently reversible.
- Delete old code only in a refactor step after replacement tests are green.

## 6. Delivery tranches

### Tranche 0: Freeze the baseline

Purpose: establish evidence before changing formats or interfaces.

Red tests and characterization fixtures:

1. Write metadata and representative WAL records with Qraft's internal file
   backend; store the resulting files as immutable versioned test fixtures.
2. Cover append-only history, suffix replacement, null/empty payload, Unicode
   command bytes, and a WAL beginning after compaction.
3. Store a legacy `snapshot.dat` fixture from the internal backend.
4. Record production configuration selection and snapshot defaults.
5. Run the complete reactor and record its test count.

Exit gate:

- Fixtures are checked in with a short format manifest and hashes.
- The full baseline suite is green.

### Tranche 1: Validate and upgrade RaftLog

Purpose: prove the external release provides the required contract before rewiring
Qraft.

Red tests against real `FileRaftStorage`:

- Metadata survives close and reopen.
- Append does not count as the Qraft durability boundary until followed by `sync`.
- Append, sync, close, reopen, and replay preserve index, term, and bytes.
- Truncate suffix plus append plus one sync produces the expected logical history.
- Prefix compaction retains later indexes and survives reopen.
- A second opener cannot acquire the same directory.
- A complete bad-CRC record fails and fences storage.
- A structurally incomplete final record follows the documented recovery behavior.
- Operations after fencing fail rather than silently continuing.

Implementation:

1. Confirm the required RaftLog release is available from the configured artifact
   repository; a sibling checkout must not be a production build dependency.
2. Upgrade the pinned dependency.
3. Run Qraft tests against that exact binary.

Integration decision:

RaftLog `AppendPlan` uses a one-based list. Qraft excludes its snapshot sentinel,
passes a one-based view relative to `snapshotLastIndex`, and translates the
planned truncation point back to an absolute index. This keeps the public library
contract unchanged while correctly supporting compacted Qraft logs.

Exit gate:

- The released library passes all Qraft-owned real-storage contract tests.
- Snapshot-relative append planning is validated at the Qraft integration boundary.

### Tranche 2: Introduce durable `SnapshotStore`

Purpose: separate application snapshots from WAL persistence before enabling real
prefix compaction.

Red tests:

- Fresh storage loads no snapshot.
- Save, close, reopen, and load preserve bytes and boundary metadata.
- Replacing a snapshot is atomic.
- A failed temporary-file write preserves the previous valid snapshot.
- Checksum or header corruption fails loudly.
- An incomplete unpublished temporary file cannot replace a valid snapshot.
- The legacy `snapshot.dat` fixture is readable and migrates on the next save.
- Empty payload and negative boundary values are rejected according to contract.
- Two instances cannot concurrently own the same snapshot location when that
  would bypass node-directory ownership.

Implementation:

1. Add `SnapshotStore` and immutable `SnapshotData` in the reusable Raft boundary.
2. Implement `FileSnapshotStore` with write, force, atomic move, and best-effort or
   required directory force according to platform guarantees.
3. Add `InMemorySnapshotStore` under test fixtures.
4. Keep snapshot format versioning independent from command protobuf versions.

Exit gate:

- Snapshot recovery works without any Qraft WAL implementation.
- Legacy snapshot compatibility is proven by fixture, not assumption.

### Tranche 3: Rewire RaftNode metadata and append paths

Purpose: use the external interface for ordinary Raft persistence while snapshots
remain uncompacted.

Red call-order tests:

- A leader does not add a submitted command to memory before append and sync.
- Append failure leaves memory unchanged and fails the client operation.
- Sync failure leaves memory unchanged, fails the operation, and fences the node.
- A follower persists truncate, append, and sync before changing memory or
  returning success.
- A persistence failure returns unsuccessful `AppendEntries`.
- Term and vote are durable before a vote is granted.
- A metadata durability failure does not grant a vote.
- Shutdown closes the external storage exactly once.

Implementation:

1. Add direct RaftLog storage construction to the composition root.
2. Change `RaftNode.Builder` and fields to accept the external interface.
3. Convert `CompletableFuture` only at the current Qraft runtime boundary.
4. Delegate conflict calculation to the corrected RaftLog `AppendPlan`.
5. Keep automatic snapshot compaction disabled until Tranche 4 is green.

Exit gate:

- All ordinary metadata, leader append, follower replacement, restart, and
  leadership tests pass with real RaftLog.
- No production RaftNode path references the duplicate Qraft SPI.

### Tranche 4: Rewire recovery

Purpose: prove Qraft can rebuild its logical state from a separate snapshot and
the external WAL.

Red tests:

- No snapshot plus full WAL rebuilds the state machine.
- Snapshot plus uncompacted overlapping WAL skips matching entries through the
  included boundary and applies later committed entries.
- Snapshot plus compacted WAL accepts a first log index greater than one.
- A conflicting entry at the snapshot boundary fails recovery.
- A gap after the snapshot boundary fails recovery.
- Invalid command bytes fail startup without partially publishing readiness.
- Multi-node restart does not assume uncommitted replayed suffix entries are
  committed.
- Single-node restart applies the recoverable committed history consistently.

Implementation:

1. Load metadata from RaftLog and snapshots from `SnapshotStore` independently.
2. Validate boundary term, replay ordering, gaps, and overlaps.
3. Restore state before applying eligible later entries.
4. Preserve explicit persisted, committed, and applied indexes.

Exit gate:

- Durable catalog and KV recovery pass using only RaftLog plus SnapshotStore.

### Tranche 5: Enable snapshot compaction and installation

Purpose: replace the adapter's volatile snapshot and no-op compaction behavior.

Red tests:

- Snapshot save completes before `truncatePrefix` begins.
- Snapshot save failure prevents prefix compaction and memory trimming.
- Prefix-compaction failure prevents memory trimming and fences the node when the
  WAL reports an uncertain publication failure.
- Successful snapshot, compaction, close, reopen, and replay restore exact state.
- A crash-equivalent reopen after snapshot publication but before compaction is
  safe with overlapping WAL entries.
- A lagging follower receives a snapshot after the leader has compacted.
- The follower durably publishes an installed snapshot before compacting its WAL
  or restoring in-memory state.
- Post-snapshot entries replicate normally after installation.

Implementation:

1. Route `takeSnapshot` through `SnapshotStore.saveAtomically`.
2. Delegate physical prefix compaction to RaftLog.
3. Trim the in-memory prefix only after both operations succeed.
4. Route leader snapshot reads and follower installation through SnapshotStore.

Exit gate:

- Automatic snapshots can be enabled safely for the production configuration.
- Restart and follower catch-up both use durable snapshots.

### Tranche 6: Prove on-disk migration

Purpose: move existing Qraft file-backend users without data loss.

Red compatibility tests:

- Current RaftLog opens and replays each internal Qraft WAL fixture.
- It loads the corresponding metadata exactly.
- It can append, sync, close, and reopen after reading a legacy Qraft WAL.
- Suffix replacement works after opening that WAL.
- Legacy application snapshots load through the new SnapshotStore.
- The whole node recovers from a copied legacy node directory.

Migration policy:

- If byte compatibility is proven, treat `qraft.raft.storage.type=file` as a
  deprecated alias for RaftLog for one release and emit an actionable warning.
- Never select a parser by guessing after a partial open. Identify legacy formats
  before mutation and preserve the directory on failure.
- Back up or copy fixtures before testing migration writes.

RocksDB requires a separate decision because its data is not a RaftLog WAL. To
remove duplicated production behavior correctly, either:

1. Declare it experimental and unsupported for upgrade, fail startup with explicit
   guidance, and remove it; or
2. Build a one-shot offline exporter that reads metadata, logical log, and snapshot
   into a new directory, validates the new RaftLog directory, and never mutates the
   source.

An online dual-write migration is prohibited because it introduces two competing
durability authorities.

Exit gate:

- File-backend migration is fixture-proven.
- The RocksDB policy is explicit and tested.
- Failed migration leaves the source directory untouched.

### Tranche 7: Remove duplicate production code

Purpose: make delegation structurally enforceable.

Refactor only after Tranches 1 through 6 are green:

- Delete Qraft's `RaftStorage` interface.
- Delete Qraft's `storage.file.FileRaftStorage`.
- Delete `RocksDbRaftStorage` after the selected migration/deprecation path.
- Delete `RaftLogStorageAdapter` once direct use is complete.
- Replace `RaftStorageFactory` with a narrow persistence composition factory.
- Move `InMemoryRaftStorage` to test sources or replace it with focused test
  fixtures implementing the external interface.
- Remove RocksDB and obsolete executor dependencies if unused.
- Remove stale configuration values, aliases after their compatibility window,
  comments, and tests.

Structural tests and checks:

- Production Qraft has no WAL magic, record-type, CRC, or replay parser constants.
- Production Qraft does not open `raft.log` or `meta.dat` directly.
- Every WAL call resolves to `dev.mars.raftlog.storage.RaftStorage`.
- Snapshot code never lives in a RaftLog adapter.
- Unsupported persistence operations cannot return successful no-ops.

Exit gate:

- A repository search confirms no duplicated production WAL implementation.
- The full reactor remains green after deletion.

### Tranche 8: System and failure verification

Purpose: validate the composed behavior beyond interface tests.

Required scenarios:

- Three durable servers commit data, stop, and recover.
- A follower crashes after append but before response and rejoins safely.
- A leader crashes after local persistence but before client response; the client
  observes an unknown outcome and an idempotent retry converges.
- A divergent follower performs suffix replacement through RaftLog.
- Snapshot compaction occurs while one follower is partitioned; the follower later
  catches up through snapshot installation.
- WAL corruption prevents readiness and produces an actionable recovery error.
- The storage directory lock prevents two server processes using one node path.
- Container restart preserves catalog, KV, snapshot, term, and vote state.

Exit gate:

- Focused storage, controller, full reactor, and tagged container suites pass.
- Operational documentation describes backup, corruption, fencing, and migration.

Disposition (2026-09-22): complete. Scenario coverage:

| Scenario | Covering test |
|---|---|
| Three durable servers commit, stop, recover | `DockerDurableRestartTest.committedCatalogAndTermSurviveWholeClusterRestart` |
| Follower crashes after append, rejoins | `DockerDurableRestartTest.killedFollowerReplaysMissedCommitAfterRestart`; `RaftNodeRealStorageRecoveryTest` |
| Leader crashes before response; unknown outcome; idempotent retry | `DockerDurableRestartTest.retryAfterLeaderCrashConvergesToOneCatalogInstance`; `HttpApiServerTest.reportsUnknownOutcomeWhenHttpWriteTimesOut` |
| Divergent follower suffix replacement | `RaftNodeRealStorageRecoveryTest`; `RaftNodeInstalledSnapshotRealRecoveryTest` |
| Compaction during partition; snapshot catch-up | `DockerDurableRestartTest.partitionedFollowerCatchesUpBySnapshotAndSurvivesRestart` |
| WAL corruption blocks readiness with actionable error | `DockerDurableRestartTest.corruptFollowerStaysLiveButUnreadyWhileHealthyQuorumServes`; `HttpApiServerTest.corruptWalFencesStartupWithoutMutatingTheEvidence` |
| Directory lock across processes | `RaftStorageProcessLockTest`; `DockerDurableRestartTest.secondContainerCannotOwnAnActiveNodeVolume` |
| Container restart preserves catalog, snapshot, term, vote | `DockerDurableRestartTest.snapshotAndPostSnapshotWalSuffixSurviveWholeClusterRestart` |

Production changes made during this tranche: typed `CommandOutcomeUnknownException`
mapped to HTTP `outcome_unknown` with `retryable: true`; leadership no-op entry
after recovery with uncommitted suffix; fenced recovery leaves the node live but
unready instead of failing startup; `FileSnapshotStore` refuses to open over an
unpublished first-snapshot temporary. Operator procedures are in
[`RAFT_STORAGE_OPERATIONS.md`](../RAFT_STORAGE_OPERATIONS.md).

## 7. Test fixture design

Use a small set of reusable fixtures:

- `RecordingRaftStorage`: records method order and allows a named call to fail.
- `FencedRaftStorage`: simulates a permanent post-durability failure state.
- `InMemorySnapshotStore`: defensively copies snapshots and supports failure points.
- `LegacyNodeDirectory`: copies immutable WAL and snapshot fixtures to a temporary
  directory before every migration test.
- `RaftPersistenceHarness`: opens real RaftLog and SnapshotStore together and
  always closes both.

These are narrow purpose-built test implementations. Protocol and filesystem
compatibility claims must still be tested with the real library.

## 8. Implementation decisions to resolve first

1. Confirm the next published RaftLog version containing prefix compaction and the
   required offset-aware `AppendPlan` change.
2. Decide whether Qraft uses RaftLog's interface directly or a temporary internal
   future-conversion helper. A second storage SPI is not an option.
3. Select and document the new snapshot file format and legacy reader.
4. Decide the RocksDB removal or offline-export policy.
5. Decide whether `syncEnabled=false` remains available outside test code. The
   recommended answer is no for production server mode.
6. Define node behavior after a fenced WAL: leave readiness, reject RPC success,
   stop participating, and require a fresh storage instance or process restart.

## 9. Risks and controls

| Risk | Control |
|---|---|
| Prefix compaction deletes the only recoverable history | Durable snapshot publication and reopen verification before compaction tests are allowed to pass. |
| Existing Qraft WAL is subtly incompatible | Immutable cross-reader fixtures and whole-node recovery tests. |
| An adapter hides unsupported behavior | Direct external interface usage and structural repository checks. |
| Memory changes precede durable storage | Recording call-order tests at leader, follower, vote, and snapshot paths. |
| A durability failure is retried on a fenced instance | Explicit node-fencing state and post-failure tests. |
| Snapshot and WAL operations race | Single Raft state transition owner plus independently serialized storage operations. |
| Tests pass only with in-memory storage | Real filesystem storage is mandatory at every durability and migration gate. |
| Removing RocksDB strands data | Explicit offline export or declared unsupported migration, never silent fallback. |

## 10. Definition of done

The externalisation objective is complete only when:

- RaftLog is the sole production owner of term, vote, and WAL persistence.
- All RaftLog operations are invoked directly or through transparent type
  conversion: open, update metadata, load metadata, append, truncate suffix, sync,
  replay, truncate prefix, and close.
- Qraft owns durable application snapshots through a separate tested store.
- RaftLog `AppendPlan` handles Qraft's compacted-log base index and Qraft delegates
  conflict planning to it.
- Persist-before-response and persist-before-memory ordering are failure-tested.
- Fencing propagates from storage to node readiness and participation.
- Existing file WAL and snapshot directories have a proven migration path.
- The internal file WAL, RocksDB Raft store, duplicate SPI, and semantic adapter
  are removed from production code.
- No production method returns success for an unsupported durability operation.
- Restart, partition, snapshot installation, and corruption scenarios pass with
  the real external library.
- The complete Maven reactor and container acceptance suite pass.
