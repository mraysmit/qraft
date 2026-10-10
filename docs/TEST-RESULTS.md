# Test results

This document records what investigations made with tests have found: what was run, what it showed, and
what it did not show. The newest entry is first. [TESTING.md](TESTING.md) says how tests are run, and
[JENKINS.md](JENKINS.md) records the Jenkins builds.

## 2026-10-10: a server killed while it writes its first snapshot

### Summary

- A server that is hard-killed while it writes the first snapshot of its life refuses to start again. It
  stays down until someone rebuilds it from its peers.
- This is the documented design, not a defect in the server. [RAFT_STORAGE_OPERATIONS.md](RAFT_STORAGE_OPERATIONS.md)
  says a temporary snapshot file with no published snapshot "is preserved as crash evidence and fences
  startup".
- The exposure is one window: from the creation of `snapshot.dat.tmp` to its rename to `snapshot.dat`,
  during the first snapshot only. A kill at any other point, and any kill once a snapshot is published,
  leaves a server that starts by itself.
- Nothing is lost in that window. The log still holds every entry, and the server starts from the log
  alone, with its whole state, once the temporary file is out of its storage directory.
- This window caused an intermittent failure of one Docker test on Jenkins. The tests are fixed. The
  server is unchanged, and whether it should change is an open decision, described at the end.

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

### Results

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

### What the results mean

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
  is installing goes through the same store, so the same refusal is to be expected. No test of its own
  shows it.
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

Not changed: the server. It refuses to start exactly as before.

### Open decision

Should a server recover by itself from a kill in this window?

It could. When it finds a temporary snapshot file and no published snapshot, and its log has never been
compacted, the temporary file is the remains of an interrupted first snapshot and the log is complete. The
server could move the file aside, keeping it as evidence, and start from the log.

It should keep refusing when it finds a temporary file, no published snapshot, and a log that has been
compacted. There a published snapshot has gone missing, and the log alone is not the whole state.

This is a change to recovery code and has not been made. Until it is decided, an operator who meets the
refusal follows "Recover a corrupt replica from peers" in
[RAFT_STORAGE_OPERATIONS.md](RAFT_STORAGE_OPERATIONS.md).

### The run

- 2026-10-10, 15:33, on the development machine, with JDK 27, on top of commit `122d739`.
- Selection: `-Dtest=RaftNodeRealSnapshotRecoveryTest,FileSnapshotStoreTest,RaftNodeInstalledSnapshotRealRecoveryTest`.
- 25 tests, none failed or skipped, in 27.6 seconds: 14 in `RaftNodeRealSnapshotRecoveryTest`, 4 in
  `FileSnapshotStoreTest`, 7 in `RaftNodeInstalledSnapshotRealRecoveryTest`.
- Output: `logs/qraft-tests-2026-10-10_15-33-10-939.log`, a local file that git ignores. The run wrote
  eight log files, seven of them from helper JVMs. They hold 4 flagged ERROR events, from one test that
  injects a failure, and no error or stack trace without a flag.
- The seven new tests passed at their first run. They describe what the server already does, so there was
  no red state to see.
- Jenkins had not yet run the seven new tests when this was written. They are in the default suite, so
  the next build runs them.
