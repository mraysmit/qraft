# Raft Storage Operations

This runbook covers the durable storage owned by a Qraft server. Apply every
procedure to one node at a time unless the whole cluster is intentionally shut
down. A healthy quorum must remain available whenever a node is rebuilt.

## Storage layout and configuration

Set `server.raft.storage.type` to `raftlog`, `server.raft.storage.path` to a
persistent, node-specific directory, and `server.raft.storage.fsync` to `true`
in the versioned JSON configuration file. In containers, the path is normally
`/app/data`, backed by a separate named volume for each server. For example,
the `storage` object inside `server.raft` is:

```json
{ "type": "raftlog", "path": "/app/data", "fsync": true }
```

Select the complete configuration with `qraft server --config <path>`. The
internal `qraft.raft.storage.*` names are not supported JVM configuration
overrides; production configuration comes from the JSON file.

The directory is one consistency unit:

- `server-id` contains the server's durable Raft identity, a UUID generated at
  its first start. The cluster's configuration records each server by this ID,
  so a directory without the file is a different server, even under the same
  name and address.
- `meta.dat` contains the current term and vote.
- `raft.log` contains the framed Raft WAL.
- `snapshot.dat`, when present, contains the latest published application
  snapshot and its Raft boundary.
- `snapshot.dat.tmp` is a temporary snapshot publication file. A temporary file
  beside an existing `snapshot.dat` is stale and is removed on startup. A
  temporary file with no published snapshot is what a crash leaves while a
  server writes its first snapshot. Startup moves it to
  `snapshot.dat.interrupted`, and the server starts from its WAL.
- `snapshot.dat.interrupted`, when present, is such an unpublished first
  snapshot, kept as evidence. Nothing reads it, and at most one is kept.
- The RaftLog implementation may create lock state in the same directory. Treat
  every file in the directory as implementation-owned; do not edit individual
  files.

Never share a node directory between server identities or mount one writable
directory into two server processes.

## Backup

A valid backup is a copy of the complete node directory made while that node is
stopped. Copying only `raft.log`, `meta.dat`, or `snapshot.dat` can combine
different durability boundaries and is not a valid backup.

1. Confirm the other servers are ready and retain quorum.
2. Stop the target server cleanly and confirm its process has exited.
3. Copy the entire configured storage directory, preserving file names,
   permissions, and bytes. For a container volume, use an offline helper or
   storage-platform snapshot only after the server using the volume has
   stopped.
4. Record the configured node name, durable server ID, Qraft version, time,
   source storage path, and whether the server is ever restarted after the copy.
5. Start the server and wait for `GET /health/ready` to return HTTP 200
   before operating on another node.

Do not copy a running node's WAL. A filesystem copy can observe `meta.dat`, the
WAL, a snapshot publication, and prefix compaction at different instants. Even
if every copied file is individually readable, their combined state is not a
supported recovery point.

### Restoring storage without rolling back a voter

An offline copy is consistent at the time it is taken; that does not make it
safe to restore later under the same server ID. If the server has opened its
storage since the copy, it may have recorded a later term, vote, or acknowledged
log entry. Restoring the older `meta.dat` and WAL erases those promises while the
cluster still counts the same voter. Leader catch-up is not a safety barrier.
Raft requires term, vote, and log state to survive restarts; see
[Raft, Figure 2 and section 5.2](https://raft.github.io/raft.pdf).

Restoring a complete directory under the same server ID is permitted only when
it is the server's last durable state: the copy was made after a clean stop and
the server has never reopened its storage since. Keep the process stopped,
preserve the damaged directory, restore that complete copy to the configured
path, fix ownership, and restart. Never merge files between directories.

If the backup predates later participation, or its provenance is uncertain:

- With a healthy surviving quorum, preserve the backup as evidence and use
  "Replace a server that lost its storage" below. Start from empty storage
  under a new server ID, after authenticated removal of the old member. Do not
  transplant backup files or its `server-id` into the replacement.
- Without quorum, preserve all surviving directories and backups and keep
  failed servers stopped. Use "Recover a cluster that lost its quorum" below,
  which rebuilds a single seed under a new server ID from a surviving directory
  or from a backup. Never restore an older directory under its old server ID.

## Detecting corruption and fencing

A storage failure or an uncertain durability transition fences the node. A
fenced process deliberately stays observable but stops participating safely:

- `GET /health/live` returns HTTP 200 with `{"status":"alive"}`.
- `GET /health/ready` returns HTTP 503 with `{"status":"fenced"}`.
- `GET /raft/status` reports `"fenced":true` and `fenceReason`, the original
  failure's exception type and message. A healthy node reports `fenceReason: null`.
- When a transition first fences the node, `RaftTransitionSequencer` logs
  `Raft transition sequencer fenced: <exception type>: <message>` at ERROR,
  with the original stack trace. Later fence attempts keep the first reason
  and emit no further fence announcement. The failing operation's own error
  remains in the log. Failed startup exposes its retained recovery or transport
  failure in `fenceReason` and retains its existing startup error logs.
- Logs identify the affected path and the underlying cause. WAL corruption
  diagnostics include `raft.log` and a byte position; directory-lock failures
  name the directory and indicate that another process may hold it.
- A server whose WAL is compacted further than its published snapshot reaches
  is fenced at startup. Its log names both indexes: "cannot recover: its log
  is compacted through index N but its published snapshot reaches only index
  M". A published snapshot is missing, or has been replaced by an older one.

Do not repeatedly restart a fenced node and do not modify or truncate the WAL.
The process does not unfence in place. Recovery uses the unchanged last durable
state where repair is possible, or the replacement procedure below; an older
backup must not be restarted as the same voter.

### Recover a corrupt replica from peers

Use this procedure only when the remaining servers are healthy, contain the
required state, and retain quorum.

1. Remove the fenced node from traffic and stop its server process.
2. Move or snapshot its complete storage directory to a read-only evidence
   location. Preserve the original bytes and logs.
3. Replace the server as described in "Replace a server that lost its storage"
   below. An empty directory gives the server a new server ID, so the cluster
   does not take it back until its old entry is removed.

Recover only one replica at a time. If no healthy quorum remains, preserve all
storage and stop this procedure; use "Recover a cluster that lost its quorum"
below. Wiping another node can destroy the remaining recovery evidence.

A server fenced because its WAL is compacted further than its published
snapshot reaches is handled the same way. The entries between the two indexes
are gone from that server, and it may have acknowledged them, so rebuild the
replica from healthy peers. Do not copy a `snapshot.dat` into the directory
from a backup or from another server.

An unpublished first snapshot does not stop a server. Until 2026-10-10 it did;
[TEST-RESULTS.md](TEST-RESULTS.md) has the reasons for the change and its tests.
A server that is killed while it writes the first snapshot of its life finds
`snapshot.dat.tmp` and no `snapshot.dat` when it starts again. Its WAL is still
complete then. It logs a warning, keeps the file as `snapshot.dat.interrupted`,
and starts from the WAL. No action is needed. Do not rename either file to
`snapshot.dat`.

## Replace a server that lost its storage

A server whose storage directory is empty starts with a new server ID. The
cluster still records the old server ID under its name and address, and that
entry still counts as a voter. A join from the new server ID is refused while
the old entry is there. Authenticated operator removal is the procedure for a
replacement that reuses a name or address. Automatic cleanup may remove a failed
entry only when its own limits permit; with the default minimum of three voters,
it cannot remove one from a three-voter cluster before a replacement joins.
Until removal commits, the new server keeps asking, once a second, and takes
nothing from the leader. A join never evicts a member.

Use this procedure for a wiped or rebuilt directory, and as the last steps of
recovering a corrupt replica. The other servers must be healthy and hold a
quorum without the server being replaced. Every server needs the same
`server.operator.token` in its configuration; without one, operator removals
are refused. Automatic cleanup is controlled independently by membership policy.

1. Stop the server being replaced, if it is running, and confirm its process
   has exited.
2. List the configuration from any healthy server, and note the server ID of
   the old entry:

   ```text
   qraft operator raft list-peers --http-addr <host:port>
   ```

3. Remove the old entry, by ID or by name. Any server forwards the request to
   the leader:

   ```text
   qraft operator raft remove-peer --name <name> --http-addr <host:port> --token-file <path>
   ```

   - Exit code 0 means removed.
   - A refusal that names the quorum rule means too few of the remaining
     voters have answered the leader recently. Restore them first; do not
     retry blindly.
   - "No leader could be reached", or a leader that has not yet committed an
     entry in its term, is worth retrying after a moment.
4. Run `list-peers` again and confirm the old entry is gone.
5. Make sure the storage path is an empty directory with the correct owner and
   permissions, then start the server with its usual name, address, and
   `server.raft.nodes`.
6. The server asks to join and is added as a non-voter. The leader promotes it
   to a voter once it has caught up and stayed healthy for the stabilization
   period (10 seconds by default). When its log history has been compacted,
   the leader installs a snapshot first.
7. Before returning it to service, require:
   - `list-peers` shows it under its new server ID with `voter` true;
   - HTTP 200 from `/health/ready`, and `"fenced":false` from `/raft/status`;
   - matching catalog data.

Notes:

- **Remove first, start second.** It is safe to start the empty server before
  the removal, but it only waits and logs a refused join every second.
- **A server that reports `removed`.** If `/raft/status` on the replacement
  shows `removed`, it holds a configuration that leaves it out and has stopped
  asking to join. Stop it, empty its directory again, and repeat from step 4.
- **Never copy `server-id`** from the old directory or from another server to
  skip the removal. The old server ID vouched for entries the empty directory
  no longer holds; reusing it can lose committed data.
- **A server that moved with its storage** is a different case. It keeps its
  server ID, asks to rejoin from its new address at startup, has its recorded
  address updated, and keeps its vote. No removal is needed.
- **Two servers lost at once in a cluster of three** leaves no quorum, so the
  removal cannot commit. Preserve surviving storage and backups and follow
  "Recover a cluster that lost its quorum" below; restoring an older directory
  under an existing voter ID is not a substitute.

## Recover a cluster that lost its quorum

Use this only when too few voters remain to elect a leader and none can be
brought back with its own directory (for a cluster of three, two are lost for
good). It rebuilds **one seed** from a stopped directory, under a **new server
ID**, in which the old configuration is replaced by a single voter. Every other
server then joins the seed empty. The same procedure restores a whole lost
cluster from a backup.

What it does and does not give you:

- The seed holds every entry in the source directory. From a surviving
  directory that is everything the survivor had. From a backup it is exactly
  what preceded the copy; later writes are lost, and the procedure cannot
  recover them.
- The old server IDs never return. The seed and the servers that join it refuse
  appends, snapshots and campaigns meant for old IDs, and the old configuration
  counts no reply from the new ones.
- Nothing is edited inside a data directory by hand. The command copies the
  source, appends one configuration entry and publishes the copy.

### Rules before you start

1. **Keep every excluded server stopped.** That is the seed's source, the other
   survivors and the lost servers. Server IDs keep the two clusters apart, but
   an excluded directory that holds an old majority can still start and serve
   its own clients as the old cluster. Each excluded server stays stopped until
   its directory is wiped.
2. **Preserve everything.** Do not wipe, move or repair an excluded directory
   until the recovered cluster is healthy again; they are the evidence.
3. **Pick the source.** With surviving directories, use the one with the highest
   last log index and term (`inspect` shows both). With none, use the newest
   backup that was taken from a stopped server and copied completely.
4. **A source on read-only media** is first copied to writable storage, as a
   complete offline backup copy.
5. **The source's parent directory must be persistent and writable.** The
   command creates a one-time claim file beside the source,
   `<source>.qraft-recovery.json`, and stages and publishes the seed in the
   output directory. In containers, mount the source volume inside a persistent
   recovery volume, for example at `/recovery/source`, and keep the claim, the
   staging copy and the output (`/recovery/seed`) on that volume. A source
   mounted at a mount root such as `/app/data` would put the claim in the
   container's throwaway layer.
6. **The commands are offline.** Nothing may hold the source directory's lock;
   a running server makes both commands refuse.

### Procedure

1. **Inspect the source.** The command prints the directory's state and whether
   ordinary startup would accept it, and changes nothing.

   ```text
   qraft operator raft inspect --dir /recovery/source
   ```

   Observed in the container scenario, for a stopped survivor:

   ```text
   Server ID           03648846-07ab-4d0b-9b94-2ea1d62547fe
   Term                1
   Last log index      9
   Snapshot index      8
   Configuration from  SNAPSHOT
   Ordinary startup    accepted
   ```

   Record the server ID: the recovery input must repeat it. If ordinary startup
   is refused, the report says why; recovery proceeds only from a directory the
   ordinary storage and recovery checks accept.

2. **Write the recovery input.** It is a version 1 JSON file; unknown keys are
   refused. `source.serverId` must equal the ID `inspect` printed, and
   `serverConfig` is the server JSON the seed will start with. That file
   supplies the output directory (`server.raft.storage.path`), the seed's name
   (`server.id`) and its Raft address, so point its storage path at a new
   directory on the persistent volume.

   ```json
   {"version":1,
    "source":{"path":"/recovery/source","serverId":"03648846-07ab-4d0b-9b94-2ea1d62547fe"},
    "serverConfig":"/etc/qraft/server.json"}
   ```

3. **Recover.**

   ```text
   qraft operator raft recover --input /etc/qraft/recovery.json
   ```

   Exit codes: 0 done, 1 refused, 2 invalid input. Observed:

   ```text
   Outcome             recovered
   Output              /recovery/seed
   Server ID           287be963-4fe3-4304-92c6-1eda38229cba
   Recovery index      10
   Recovery term       2
   ```

   The command holds the source's lock, copies it byte for byte and compares
   checksums, opens the copy with the ordinary checks, appends one entry that
   lists the seed as sole voter, in a term above the source's, and publishes the
   copy with the new server ID. The source is never changed.

   **One-time use.** A second `recover` from the same source with a different
   output is refused, so one source cannot seed two clusters. After a complete
   recovery, the same input exits 1 and changes nothing. If the command was
   interrupted, run the same input again: before publication it starts again
   from the unchanged source with a new ID; after publication it records the
   result and exits 0. A staging directory is never accepted as a data
   directory.

4. **Start the seed** with the server JSON used above, keeping the same name
   and address the old server had. Check that:
   - `/raft/status` shows it as leader, with its new server ID;
   - `list-peers` and `/v1/operator/raft/configuration` list only the seed, as
     a voter;
   - the registrations you expect are present, and a test write commits.

5. **Rejoin the others empty.** For each remaining server, wipe its data
   directory (the old one is evidence you may now discard), then start it with
   its usual server JSON. It gets a new server ID, joins the seed through the
   ordinary path and is promoted under the automatic promotion policy. Do not
   list a still-running excluded server among a wiped server's contacts. Watch
   the configuration until all servers are voters under new IDs and none of the
   old IDs is among them.

   The recovered cluster starts below `cleanup.minVoters` and grows by joins;
   cleanup never takes it below that minimum.

### What has been observed

`DockerLostQuorumRecoveryTest` runs the procedure on a real three-server
cluster in containers, from the `qraft-runtime:test` image:

- **From current directories.** A write is committed and snapshotted, two
  servers are stopped and then the survivor. `inspect` accepts the survivor's
  directory, `recover` publishes a seed with a new ID, the seed leads alone and
  holds the write. The two old servers are then restarted on their own
  directories on purpose: they elect a leader and commit their own write, and
  the seed's replies are logged as ignored under its new server ID. The
  seed's state, term, configuration and data are unchanged, and the old
  cluster's write never reaches it. After the old servers are wiped they join
  the seed, are promoted, and leave three voters under new IDs, none of them
  old. A new write reaches all three and the old cluster's write is gone.
- **From a backup.** A stopped server is copied completely, a second write is
  committed, and the whole cluster is stopped. The seed recovered from the
  backup holds the first write and not the second, and the same isolation and
  rejoin results follow.

Both scenarios passed in five consecutive runs and in Jenkins build 33. Their
only logged errors are the peer-unreachable errors of the stopped servers,
declared by the test.

### Limits

- The procedure restores the **durable state of one directory**. Anything
  committed only on servers that are lost, or after a backup, is gone. Check
  registrations before returning clients to the cluster.
- A registration that a client was told succeeded is absent after a restore
  from a backup if it followed the backup. `DockerLostQuorumRecoveryTest`
  shows it: a write acknowledged with status 200 and committed on all three
  servers after the backup is missing from the recovered seed and from the
  rejoined servers. Clients must register again.
- It is not an upgrade or migration tool, and it is not a way to change
  membership while a quorum remains; use the join and removal procedures
  above for that.
- The command's own logs use the production Logback defaults (`./logs` under
  the `server` mode name, plus the console). A separate operator log mode is
  not provided.

## Automatic failed-voter cleanup

The leader evaluates continuous absence of valid peer replies and removes failed
voters through committed membership entries. Only applied heartbeat rounds count;
a delayed loop does not invent elapsed failure rounds. Every new leadership
starts a fresh failure grace. Replies from an old leadership or a different
durable server ID do not refresh that grace.

The optional `membership` object inside `server.raft` has these defaults:

```json
{
  "promotionStabilizationMs": 10000,
  "promotionMaxTrailingEntries": 250,
  "cleanup": {
    "enabled": true,
    "failureTimeoutMs": 60000,
    "intervalMs": 10000,
    "minVoters": 3
  }
}
```

Omitted failure and interval values increase to the configured election timeout
and heartbeat interval respectively when those exceed the usual defaults.
Explicit failure timeouts must be at least the election timeout; intervals must
be at least the heartbeat interval. Both and `minVoters` must be positive.
Promotion settings may be zero. Unknown settings and malformed values fail
configuration validation. Disabling cleanup does not relax validation or
disable operator removal.

A pass freezes its committed voter count `N`, eligible IDs and removal budget
`floor((N - 1) / 2)`. It rechecks eligibility, the minimum and recent contact with
quorums of both the current and proposed configurations before each proposal.
It changes one voter at a time and waits for commitment. Recovered candidates
are skipped; a failed proposal, unrelated membership change or leadership loss
abandons the pass. Cleanup does not remove non-voters or the leader, and does
not recover a cluster that has lost quorum. Failure history itself is never
persisted as voting membership.

A committed promotion requests an early cleanup pass, using the same safety
checks. For automatic replacement, join a server with a distinct name and
address, let it catch up and promote, then let cleanup evaluate failed voters.
For a replacement reusing a name or address, use the authenticated remove-first
procedure above. An operator can remove a member below the automatic minimum
when the existing operator quorum checks permit it.

Startup logs `Raft membership policy` with the effective settings and rounded
heartbeat durations. The leader logs eligible IDs, proposed removals and
committed removals at INFO; DEBUG logs report deferral and pass cancellation
reasons. The Step 5 record documents focused/manual-timer and real-WAL evidence;
the broader real replacement and cleanup container matrix remains Step 8 work.

## Directory-lock failures

RaftLog holds an exclusive lock for the lifetime of the storage instance. A
second JVM or container pointed at the same directory must fail startup; the
existing owner continues serving.

When this occurs:

1. Identify the process or container that owns the node directory.
2. If it is the intended server, leave it running and correct the contender's
   storage path or volume mapping.
3. If the owner is stale, stop it cleanly and verify it has exited before
   restarting the intended server.
4. Never bypass, delete, or replace lock state while an owner process is alive.

## A warning in the log of a server that is stopping

This one is not about storage. It is here because this is the runbook an
operator reads when a server's log shows something unexpected.

A server that is stopping can log this warning once, with a stack trace:

```text
WARN  io.netty.channel.ChannelInitializer - Failed to initialize a channel. Closing: [id: 0x81d5c86d, L:/10.0.0.5:9080 - R:/10.0.0.6:43752]
io.netty.util.IllegalReferenceCountException: refCnt: 0, increment: 1
	at io.grpc.netty.NettyServer$1.initChannel(NettyServer.java:278)
```

- **What happened.** A peer connected to the server's Raft port in the instant
  the server stopped. gRPC's server had released its shared resources and had
  not yet marked itself terminated, and the connection was prepared between
  those two steps. A connection prepared after them is closed without a
  message.
- **What it costs.** One refused connection to a server that was stopping
  anyway. The peer reads a closed connection and tries again by itself. No
  state is touched: the server's Raft storage is closed by its own shutdown,
  which this does not interrupt.
- **What to do.** Nothing, when the warning is in the last lines of a server
  that was told to stop. Investigate when it appears on a server that is
  running and was not told to stop: that is not this case.
- **Why Qraft does not prevent it.** The window is inside the gRPC library.
  Two tests of `GrpcRaftServerTest` place a connection in the window and after
  it, so a change in the library's behaviour is seen.

## What to expect when servers return, leave or stop

These behaviours were observed in the container scenarios (membership Step 8)
and are not faults.

- **A voter that returns after a long stop can briefly replace the leader.** While
  it was down it heard no heartbeats and kept raising its term. The leader's
  connection to it may still be backing off, so it can campaign for some seconds
  after it starts. Leader stickiness makes the other voters ignore its
  campaigns, but when the leader's next AppendEntries reaches it, its answer
  carries the higher term and the leader steps down. Elections follow, and the
  cluster has a new leader within seconds. Nothing committed is lost. The
  Pre-Vote extension (Raft thesis section 9.6) would prevent it and is not
  implemented. Expect extra elections, and a leader that is not the one you
  had, after restarting a long-stopped voter.
- **A removed server never learns of its removal and keeps campaigning.** Once a
  server is outside the configuration the leader stops replicating to it, so it
  keeps its old configuration, does not report `removed`, and raises its term
  every election timeout (one test run found it at term 31). The voters ignore
  it and the cluster keeps its leader and term. Stop it and wipe its directory;
  do not leave it running. A leader that removes itself does step down and
  reports `removed`.
- **The failure grace belongs to the current leader.** Automatic cleanup counts
  absence in the leader's memory, so a leadership change restarts every grace. A
  voter that comes back and is stopped again is never found eligible unless a
  single absence lasts the whole grace.
- **Cleanup waits for the minimum.** With three voters and `minVoters` of 3, a
  failed voter is found eligible on every pass and kept. It is removed when a
  replacement's promotion commits, which requests an early pass.
- **A snapshot to an unreachable lagging peer is retried less often.** The leader
  waits 250 ms after the first failed attempt, doubling with each consecutive
  failure to the same peer up to 5 seconds, and starts again after a delivered
  snapshot or a new leadership. Each failed attempt is still logged as an error
  with its stack trace.
- **A transition refused because the node is shutting down is logged at debug.**
  A vote or snapshot response, a leadership no-op or a snapshot request that
  reaches a stopping node is turned away before anything is written. Any other
  failure of such a transition is an error.

## Upgrade and migration

Current Qraft supports only the external `raftlog` backend. The former `file`
configuration value and RocksDB backend are not accepted production storage
types.

Legacy Qraft file-backend bytes are compatibility tested at the storage-library
boundary: the current RaftLog reader can load legacy `meta.dat` and `raft.log`,
append, replace a
suffix, sync, close, and reopen. Qraft's snapshot store can read the legacy
`snapshot.dat`; the next successful snapshot publication writes the current
versioned format.

Catalog snapshot payloads and WAL commands written before composite service
identity also decode directly. Missing tenant and namespace values become
`default`, missing datacenter and region values become empty strings, and missing enabled state
becomes `true`. No operator action, offline rewrite, or coordinated data migration
is required for these payload defaults; immutable fixture tests cover both legacy
command bytes and snapshot documents. This does not establish whole-node startup
compatibility: the Raft configuration and snapshot envelope must also be present.

Node commands, and the nodes in a snapshot, written before 2026-10-09 decode
directly as well. The fields a node no longer has, among them its host name, port,
and capabilities, are ignored. A removed node status reads as `healthy` or
`unreachable`, by whether the node was in contact, and an entry that holds the
removed capabilities update applies nothing. Immutable fixture tests cover these
too.

A directory that holds Raft state but no cluster configuration, which is any
directory written before configurations were recorded in the log (2026-09-29),
refuses to start. A legacy application snapshot without the Raft configuration
envelope is likewise not a supported node recovery image. Neither lower-level
WAL readability nor catalog-payload decoding bypasses these checks.

There is no supported in-place upgrade from these directories. Preserve their
bytes and continue using the version that created them when their data must be
retained. A new cluster on empty directories starts with empty application state;
it does not migrate old data. Replacing one server from healthy peers is available
only when those peers already form a compatible, configured Qraft cluster.

For a release whose wire, command, WAL, metadata, and snapshot formats are
explicitly compatible with the currently deployed release:

1. Confirm release compatibility and that the other nodes retain quorum.
2. Stop one server, take a complete offline backup, and preserve it.
3. Keep its current directory and server ID; configure the storage through
   `server.raft.storage` in the versioned JSON file and start the new release.
4. Verify readiness, voter membership, and recovered catalog state before
   proceeding to another server.
5. On failure, stop and preserve the directory. A software rollback requires
   confirmation that the old release can read everything the new release wrote.
   Do not restore the pre-upgrade backup after the server has reopened storage;
   use the restore restrictions above or rebuild from healthy peers.

There is no supported in-place or online dual-write migration from RocksDB.
Preserve a RocksDB directory and remain on the version that created it unless a
separately supplied offline exporter is available. Such an exporter must write a
new directory, validate it, and never mutate its source.

## Verification coverage

The operational guarantees in this runbook are exercised by the following
tests:

- `DockerDurableRestartTest`: retained-volume restart, follower and leader
  crashes, corruption fencing, lock contention, unknown client outcomes, and
  snapshot catch-up followed by restart.
- `RaftStorageProcessLockTest`: exclusive ownership across separate JVMs.
- `RaftNodeMembershipTest`, `MembershipServiceTest`, and
  `RaftNodeServerIdentityTest`: a colliding join is refused until the old entry
  is removed, the removal's quorum rule and token, and a server refusing a log
  or snapshot meant for another server ID.
- `DockerServerReplacementTest`: the replacement procedure as a whole, in
  containers: a wiped follower takes a new server ID, its join is refused while
  the old entry holds the name, a removal with the wrong operator token changes
  nothing, the `remove-peer` command with the right token removes the old entry,
  and the replacement joins, is promoted, is ready and unfenced, holds the
  catalog, and keeps its identity and vote across a restart.
- `DockerLeaderRemovalTest`: the authenticated removal of the current leader
  in containers. It commits its own removal and steps down, reports `removed`
  and is not ready, the two retained voters elect one leader, and registrations
  from before the removal survive and new ones are accepted.
- `DockerFailedVoterCleanupTest`: automatic cleanup in containers with the
  default policy. A voter that has failed past the grace is found eligible but
  kept while its removal would leave fewer than three voters, and it is removed
  as soon as a replacement's promotion commits. The removal outlives restarts of
  the retained servers.
- `SnapshotRetryBackoffTest` and `RaftNodeSnapshotRetryBackoffTest`: the delay
  before a leader retries a snapshot to a peer that did not take one, from 250
  ms doubling to 5 seconds, measured on the injected clock.
- `DockerMembershipResizeTest`: a three-server cluster grown to five and shrunk
  back to three in containers while a client registers continuously. Each
  added server joins as a non-voter and is promoted, every acknowledged
  registration is on all five servers and survives the shrink, and the three
  that remain keep one leader and an unchanged term although the removed
  servers, which keep their old configuration, go on campaigning.
- `RecoveryInputTest`, `OfflineRecoveryTest`, `OfflineRecoveryProcessTest`,
  `RaftOperatorRecoveryCommandTest` and `DockerLostQuorumRecoveryTest`: the offline `inspect` and `recover` commands,
  their crash boundaries and one-time claim, separate-JVM lock refusal, and the
  two container scenarios (from current directories and from a backup),
  including the old majority restarted on purpose and the empty rejoin.
- `HttpApiServerTest`: fenced liveness/readiness, the status reason for a
  transition or startup failure, and corruption evidence.
- `RaftTransitionSequencerTest`: first-cause retention, one fence announcement,
  caller context, and unchanged later refusals.
- `RaftNodeApplyFailureTest`: a state-machine failure is retained as the fence reason.
- `RaftNodeConfigurationTest`: rejecting bootstrap before a write keeps the
  node healthy, while uncertain bootstrap persistence still fences it.
- `RaftLogStorageIntegrationTest`: corruption, locking, and legacy WAL/metadata
  compatibility against the real external library.
- `FileSnapshotStoreTest` and `RaftNodeRealSnapshotRecoveryTest`: atomic snapshot
  publication and legacy snapshot reading; crash recovery before and after
  first-snapshot publication and compaction, including preservation of an
  interrupted first snapshot and fencing when compaction exceeds the published
  snapshot boundary.
