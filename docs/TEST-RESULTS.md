# Test results

This document records what investigations made with tests have found: what was run, what it showed, and
what it did not show. The newest entry is first. [TESTING.md](TESTING.md) says how tests are run, and
[JENKINS.md](JENKINS.md) records the Jenkins builds.

## 2026-10-10: a server killed while it writes its first snapshot

### Summary

- Until 2026-10-10 a server that was hard-killed while it wrote the first snapshot of its life refused to
  start again. It stayed down until someone rebuilt it from its peers. That was the documented design.
- The exposure was one window: from the creation of `snapshot.dat.tmp` to its rename to `snapshot.dat`,
  during the first snapshot only. A kill at any other point, and any kill once a snapshot was published,
  left a server that started by itself.
- Nothing was lost in that window. The log still held every entry.
- The window caused an intermittent failure of one Docker test on Jenkins. The tests were fixed first.
- The server was then changed, the same day. It now sets the unpublished file aside and starts from its
  log. Recovery refuses the state that is dangerous instead: a log compacted further than the published
  snapshot reaches. Writing the tests for that showed two cases in which a server had been starting with
  state missing.

### How it was found

`DockerClientRecoveryTest.aKilledFollowerInstallsTheLeadersSnapshotAndThenHoldsTheLeadersHealthState` kills
a follower and starts it again. On Jenkins it timed out in builds 2 and 8, in the same 90-second wait for
the follower, and passed in builds 4, 5, and 6.

Build 2 kept no container logs. Build 8 did, and the follower's log gives the cause. Times are the
container's clock on 2026-10-10:

```text
06:46:31.548  Snapshot threshold reached: 6 entries since last snapshot, triggering snapshot
06:46:31.578  Snapshot taken at index=6, term=1, size=1830bytes, compacting 6 entries
              (no "Snapshot complete": the test killed the container here)
06:46:34.530  ERROR Failed to initialize Raft storage: Refusing startup because unpublished first
              snapshot /app/data/snapshot.dat.tmp has no published /app/data/snapshot.dat; preserve the
              file for diagnosis and restore this node from its peers
```

The process ended on that exception, so the follower never came back and the wait could not succeed.

All three servers of that cluster began their first snapshot within 115 milliseconds. The other two
began at 06:46:31.496 and 06:46:31.611, and finished in 75 and 64 milliseconds.

Build 2 failed in the same wait after the same time, 105.8 seconds against 108.1. Nothing tells the two
failures apart, but without its logs the cause of build 2 is not proven.

An earlier explanation was wrong. It blamed a deadline hidden in the client's check settings. That limit
was real and is removed (`client-long-ttl.json`), but the test timed out again with it removed.

### The tests

`RaftNodeRealSnapshotRecoveryTest` has seven tests named `firstSnapshotCrash...`, added on 2026-10-10. Each
one:

1. writes a log of five entries with the real `FileRaftStorage`, and no snapshot;
2. starts a helper JVM, `SnapshotStoreCrashWriterFixture`, that publishes a first snapshot with the real
   `FileSnapshotStore` and halts itself at one chosen point;
3. checks what the halt left on disk;
4. starts a `RaftNode` on that directory and checks its term, its snapshot index, that it has applied all
   five entries, and that it holds all four keys the log ever wrote.

A halt at a chosen point is a kill at that point, every time. Nothing in these tests depends on timing.

Six tests that were already in the class cover the same points for a server that has a published
snapshot. A seventh already tested the refusal after the forced write.

### Results, before the change

This is the server as it was when these tests were first written. The two rows that refuse no longer hold;
"The change" below has what replaced them.

A first snapshot, with no published snapshot before it:

| The kill lands | Checkpoint | Left on disk | On restart |
|---|---|---|---|
| before the temporary file is created | `BEFORE_TEMPORARY_CREATE` | the whole log | starts by itself, from the log |
| after the temporary file is written | `AFTER_TEMPORARY_WRITE` | temporary file, whole log | **refuses to start** |
| after the temporary file is forced to disk | `AFTER_TEMPORARY_FORCE` | temporary file, whole log | **refuses to start** |
| after the file is renamed into place | `AFTER_ATOMIC_PUBLICATION` | snapshot, whole log | starts by itself, from the snapshot |
| after the directory is forced | `AFTER_DIRECTORY_FORCE` | snapshot, whole log | starts by itself, from the snapshot |
| before the log is compacted | `AFTER_PUBLICATION_BEFORE_COMPACTION` | snapshot, whole log | starts by itself, from the snapshot |
| after the log is compacted | `AFTER_PREFIX_COMPACTION` | snapshot, log suffix | starts by itself, from the snapshot |

In the two refusing cases the tests go on:

- the temporary file is still there after the refusal, and the older test checks that it is unchanged,
  byte for byte;
- the log, opened by itself, still holds entries 1 to 5;
- with the temporary file moved out of the storage directory, the server starts from the log alone and
  holds all four keys.

A later snapshot, with a published snapshot already there: the server starts by itself after a kill at
each of six points, and removes the stale temporary file where there is one. The point after the
directory is forced has no test for a later snapshot.

### What the results meant, before the change

- **The window is short and happens once.** It is the time to write and force one snapshot file, during
  the first snapshot a server ever writes. In builds 8 and 9 a whole first snapshot, from its start to
  the end of compaction, took 55 to 153 milliseconds in the containers, with a median near 77. The
  refusing window is part of that.
- **When it happens depends on configuration.** A server takes its first snapshot once it has applied
  `server.raft.snapshot.threshold` entries, 10,000 by default, at its next check, every 60 seconds by
  default. The Docker acceptance profile uses 5 entries and 1 second, so there the first snapshot comes
  seconds after start. That is why a test that killed a server soon after start could hit it.
- **Servers reach it together.** Every server applies the same log, so each takes its first snapshot
  within one check interval of the others. In build 8 all three began within 115 milliseconds. One power
  loss in that moment could leave more than one server refusing to start. The procedure in
  [RAFT_STORAGE_OPERATIONS.md](RAFT_STORAGE_OPERATIONS.md) rebuilds one replica at a time from a healthy
  quorum, and stops if there is no quorum.
- **The refusal does not protect data here.** The log is compacted only after a snapshot is published, so
  a server with a temporary file and no snapshot still has its whole log.

### What the tests do not show

- **A power cut.** The helper halts its JVM, which is what a container kill does. The operating system
  still writes what the process had handed it. After a power cut the temporary file may be short or
  empty, and a rename that was not yet forced may be undone. Either way the server finds a temporary file
  and no snapshot, and refuses, but no test here cuts power.
- **A first snapshot installed from the leader.** A follower that is killed while it writes a snapshot it
  is installing goes through the same store. No test showed it when this was first written; the change
  below added one.
- **That the Docker failure cannot return.** The fix below removes the kill from the window by ordering.
  Jenkins build 9 passed with it, and so did builds 4, 5, and 6 without it. One pass is not the proof; the
  ordering is.

### What was changed, and what was not

Changed, in the tests:

- `DockerHealthApiHelper.awaitFirstSnapshotOnEveryServer` waits until every server has published its
  first snapshot, and writes filler entries through the leader while one has not. A server reports a
  snapshot index above zero only after its snapshot is published and its log compacted.
- The four Docker tests that kill a server and start it again call it before the kill: the recovery test
  above, and `killedFollowerReplaysMissedCommitAfterRestart`,
  `killedLeaderIsReplacedAndRejoinsWithCompleteCatalog`, and
  `retryAfterLeaderCrashConvergesToOneCatalogInstance` of `DockerDurableRestartTest`. Commit `122d739`.
- Jenkins build 9 passed on that commit: 941 results, none failed, and no server refused to start.
- [TESTING.md](TESTING.md) has the rule for new Docker tests.

Not changed at that point: the server. It was changed later the same day, as "The change" describes.

### The change

Made on 2026-10-10, after the owner's decision to proceed. It has two parts, and the second is what makes
the first safe.

**Part 1: an interrupted first snapshot no longer stops a server.** When `FileSnapshotStore.open` finds
`snapshot.dat.tmp` and no `snapshot.dat`, it:

- moves the temporary file to `snapshot.dat.interrupted`, a name that start-up never reads, so the evidence
  is kept;
- keeps one such file and replaces it if the same thing happens again, so the files cannot pile up;
- logs one warning that says what it found;
- opens without a snapshot, and the server starts from its log.

**Part 2: recovery refuses a log that has dropped entries no snapshot covers.** After `RaftNode` has loaded
the published snapshot and replayed the log, it compares two numbers:

- **B**, the index the log has been compacted through, 0 if it never was;
- **S**, the last index the published snapshot covers, 0 if there is none.

If B is greater than S, recovery fails: "Node <name> cannot recover: its log is compacted through index B but
its published snapshot reaches only index S; a published snapshot is missing or older than the log." The
node is then fenced, and the server stays live and unready, as it does for a corrupt log. A server always
publishes a snapshot before it compacts the log up to it, for its own snapshots and for installed ones
(design document, section 14.4). So S is below B only when a published snapshot has been lost or replaced
by an older one.

What a server does with each state it can find at start-up:

| Found at start-up | Before | Now |
|---|---|---|
| temporary file, no snapshot, log never compacted | refused to start | starts from the log; the file is kept aside |
| temporary file, no snapshot, log compacted | refused to start | keeps the file aside, then refuses: a snapshot is missing |
| no snapshot, log compacted, entries left in it | failed on a gap in the log's indexes | refuses, and says why |
| no snapshot, log compacted, no entries left, a cluster of one server | failed when it tried to write a first entry: "Append must start at index 6, got 1" | refuses, and says why |
| no snapshot, log compacted, no entries left, a server of a larger cluster | **started empty**, with its term and vote kept | refuses |
| snapshot older than the log's compaction, entries left | failed on a gap in the log's indexes | refuses, and says why |
| snapshot older than the log's compaction, no entries left | **started**, with the entries between missing | refuses |
| temporary file beside a published snapshot | removed the file and started | unchanged |

The "Before" column is what the red run showed on the unchanged server. Two rows were silent losses. For the
server of a larger cluster the log reads "Recovered 0 log entries from storage" and then "Raft node node-1
started successfully (term=3, logSize=1)". An earlier version of this document expected the cluster of one
server to start empty too; the red run showed that the log library stops it, with a message that says
nothing about a snapshot.

How the two open points were settled:

- **Where B comes from.** From `compactionBoundary()` of `FileRaftStorage`, through a test of the class in
  one method, `RaftPersistence.compactionBoundary`. Another storage implementation reports 0 and is not
  checked. The cleaner route, the method on the `RaftStorage` interface, needs a release of `raftlog-core`,
  and is a task of its own in the task list.
- **`RaftNode`.** The check is one comparison in `recoverFromStorage`. It went in ahead of the membership
  list's Step 4 gate, at the owner's word to proceed.

The tests, written first:

- `FileSnapshotStoreTest`: an unpublished first snapshot is set aside and the store opens empty; a second
  one replaces the file kept before; a file kept aside is never loaded. The test of the refusal is gone.
- `RaftNodeRealSnapshotRecoveryTest`: the two `firstSnapshotCrash...` tests of the window now expect the
  server to start from its log, hold its whole state, and keep the unpublished file unchanged under its
  new name. Six new tests cover the rows of the table that refuse. The older test of the refusal is
  removed, because the two rewritten tests cover its point. The tests of a later snapshot now also check
  that a stale temporary file is removed and not kept aside.
- `RaftNodeInstalledSnapshotRealRecoveryTest`: a follower killed while it writes a first snapshot that it is
  installing restarts from its log, and keeps the file. This closes the gap named above under "What the
  tests do not show".
- The Docker tests keep their wait for first snapshots. A kill in the window is survivable now, so the wait
  no longer guards against a failure. It keeps each run on one path.

Red, green, and mutation, all on the development machine, in "The runs" below:

- Red, on the unchanged server: 33 tests, 11 not passing, each for the reason in the table.
- Green: the same 33 pass, with 27 more from the classes nearest the changed code.
- Mutation, in an isolated copy: nine deliberate defects in the three changed classes, one at a time. All
  nine make a test fail. A tenth, the removal of `REPLACE_EXISTING` from the move that sets the file aside,
  survived and is equivalent: beside `ATOMIC_MOVE` the JDK ignores every other option.

Documents changed with it: [RAFT_STORAGE_OPERATIONS.md](RAFT_STORAGE_OPERATIONS.md), sections 14.4, 14.5,
and 22 of the design document, the rule for Docker tests in [TESTING.md](TESTING.md), and this entry.

### What other Raft implementations do

Read on 2026-10-10 in each project's public source, before the change was made, to check it against
practice.

A leftover temporary snapshot at start-up (Part 1). None of the five refuses to start:

| Implementation | What it does | Where |
|---|---|---|
| HashiCorp Raft, used by Consul, Vault, and Nomad | skips it with the warning "found temporary snapshot" | [`file_snapshot.go`](https://github.com/hashicorp/raft/blob/main/file_snapshot.go), `getSnapshots` |
| SOFAJRaft | deletes it | [`LocalSnapshotStorage.java`](https://github.com/sofastack/sofa-jraft/blob/master/jraft-core/src/main/java/com/alipay/sofa/jraft/storage/snapshot/local/LocalSnapshotStorage.java), `init` |
| braft | deletes it | [`snapshot.cpp`](https://github.com/baidu/braft/blob/master/src/braft/snapshot.cpp), `LocalSnapshotStorage::init` |
| Kafka KRaft | deletes partial snapshots as it scans the directory | [`KafkaMetadataLog.scala`](https://github.com/apache/kafka/blob/3.7/core/src/main/scala/kafka/raft/KafkaMetadataLog.scala), `recoverSnapshots` |
| etcd | deletes orphaned temporary files; renames a snapshot file it cannot read to `.broken` and uses an older one | [`snapshotter.go`](https://github.com/etcd-io/etcd/blob/release-3.5/server/etcdserver/api/snap/snapshotter.go) |

The Raft dissertation gives the reason. A snapshot is written "to a temporary file first", and renamed "when
writing is complete and has been flushed to disk; this ensures that no server loads a partially written
snapshot on startup" ([Ongaro, chapter 5](https://github.com/ongardie/dissertation/blob/master/compaction/memsnapshot.tex)).
The temporary file is never the authority.

A log compacted past what a snapshot covers (Part 2). Two of them make this check at start-up:

- Kafka KRaft throws: "Inconsistent snapshot state: there must be a snapshot at an offset larger then the
  current log start offset". The comment above the check gives the rule: if the log start offset is not 0,
  there must be a snapshot that covers the state up to it.
- etcd does not start when the snapshot its log refers to is missing: "failed to find database snapshot
  file" in [`backend.go`](https://github.com/etcd-io/etcd/blob/main/server/storage/backend.go). Its log
  reader has an error for it, "wal: snapshot not found", in
  [`wal.go`](https://github.com/etcd-io/etcd/blob/main/server/storage/wal/wal.go).

Kafka also shows what the rule is for. In [KAFKA-14238](https://issues.apache.org/jira/browse/KAFKA-14238), a
blocker fixed in 3.3.0, replicas deleted log segments that no snapshot covered and then failed with an
`IllegalStateException`.

The dissertation confirms the order the check rests on: "Once the state machine completes writing a
snapshot, the log can be truncated."

One thing the research added to the proposal. HashiCorp Raft leaves temporary directories where they are,
and its users have met snapshot directories filled with them. That is why Part 1 keeps one file and
replaces it.

Limits of this research:

- the Kafka source read is the 3.7 branch, and etcd's snapshot loading the 3.5 branch; both files have been
  reorganised since;
- Apache Ratis, TiKV, and Dragonboat were not read;
- nothing found argues for a refusal like Qraft's, and nothing found explains why a project might want one.

### Opinion

This is the recommendation of the assistant that made the investigation, recorded on 2026-10-10 before
the decision. The owner decided the same day to proceed, and "The change" above is the result.

**Make the change, both parts together.**

- The refusal as it stands costs availability and protects nothing. In the one state it reacts to, the log
  is complete, and the tests above show the server holds its whole state from the log alone.
- It can stack. Servers take their first snapshot within one check interval of each other, so one power loss
  can leave several refusing at once. Rebuilding from peers needs a healthy quorum, which is then the thing
  that is missing.
- It is not what other Raft implementations do. Five were read and none refuses on a leftover temporary
  snapshot.
- Part 2 is worth having whatever is decided about Part 1. It turns a rule the design already states,
  "a successful compaction must never be paired with an absent or volatile snapshot", into a check. Without
  it, a server whose published snapshot has gone missing could start empty.
- The cost is small: one branch where the snapshot store opens, one comparison in recovery, and about ten
  tests.

What speaks for caution:

- Part 1 must never be released without Part 2. Alone, it would let a server with a compacted log and a
  missing snapshot start as if it were new.
- Part 2 changes recovery in `RaftNode`, the code the membership list's Step 4 gate protects. The safer order
  is the gate first. The check is small enough to go ahead of it if the owner prefers.
- It depends on reading the log's compaction boundary through the library's interface. A cast would work
  today, and would be the wrong thing to keep.

Until it is decided, nothing is at risk that was not at risk before: the tests keep their kills out of the
window, and an operator who meets the refusal follows "Recover a corrupt replica from peers" in
[RAFT_STORAGE_OPERATIONS.md](RAFT_STORAGE_OPERATIONS.md).

### The runs

All on 2026-10-10, on the development machine, with JDK 27, in one visible console. The output files are
local and git ignores them.

The first-snapshot tests, on top of commit `122d739`:

- Selection: `-Dtest=RaftNodeRealSnapshotRecoveryTest,FileSnapshotStoreTest,RaftNodeInstalledSnapshotRealRecoveryTest`.
- 25 tests, none failed or skipped, in 27.6 seconds: 14 in `RaftNodeRealSnapshotRecoveryTest`, 4 in
  `FileSnapshotStoreTest`, 7 in `RaftNodeInstalledSnapshotRealRecoveryTest`.
- Output: `logs/qraft-tests-2026-10-10_15-33-10-939.log`. The run wrote eight log files, seven of them from
  helper JVMs. They hold 4 flagged ERROR events, from one test that injects a failure, and no error or
  stack trace without a flag.
- The seven new tests passed at their first run. They described what the server did then, so there was no
  red state to see.
- Jenkins ran them the same day, in build 10 on commit `aa0d876`: the 14 tests of
  `RaftNodeRealSnapshotRecoveryTest` passed on Linux, in a build of 948 results with none failed and no
  error or stack trace without a flag.

The change, on top of commit `aa0d876`:

- Red, same selection, server unchanged: `logs/qraft-tests-2026-10-10_16-37-53-377.log`. 32 tests, 4
  failures and 6 errors, the ten predicted. One more test, for a server of a larger cluster, was added and
  run by itself: `logs/qraft-tests-2026-10-10_16-39-35-503.log`, 1 test, 1 failure.
- Green, the same selection with `RaftNodeRealStorageRecoveryTest`, `RaftNodeConfigurationTest`, and
  `PackageDependencyTest`: `logs/qraft-tests-2026-10-10_16-41-20-129.log`. 60 tests, none failed. The 14
  log files of the run hold 38 flagged ERROR events and no error or stack trace without a flag. Run again
  after the last simplification, on the working tree as it was committed, with the same result:
  `logs/qraft-tests-2026-10-10_16-50-57-326.log`.
- Mutation, in an isolated copy of the repository: `logs/qraft-mutation-recovery-2026-10-10_16-43-41-441.log`
  and `logs/qraft-mutation-recovery-2026-10-10_16-49-12-840.log`. The unchanged copy passed its 33 tests before and after.
- Jenkins ran the change the same day, in build 11 on commit `f2119d3`: 956 results, none failed, every
  coverage gate met, and no error or stack trace without a flag in its 113 log files. The 19, 6, and 8
  tests of the three classes passed on Linux.
