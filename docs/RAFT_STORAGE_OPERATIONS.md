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
  temporary file with no published snapshot is preserved as crash evidence and
  fences startup.
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
  failed servers stopped. Qraft currently has no supported lost-quorum recovery
  or whole-cluster backup-restore procedure. Membership Step 7 must deliver and
  test that procedure before backups can be used to rebuild a lost cluster.

## Detecting corruption and fencing

A storage failure or an uncertain durability transition fences the node. A
fenced process deliberately stays observable but stops participating safely:

- `GET /health/live` returns HTTP 200 with `{"status":"alive"}`.
- `GET /health/ready` returns HTTP 503 with `{"status":"fenced"}`.
- `GET /raft/status` reports `"fenced":true`.
- Logs identify the affected path and the underlying cause. WAL corruption
  diagnostics include `raft.log` and a byte position; directory-lock failures
  name the directory and indicate that another process may hold it.

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
storage and stop this procedure. The lost-quorum limitation under "Restoring
storage without rolling back a voter" applies; wiping another node can destroy
the remaining recovery evidence.

An unpublished first `snapshot.dat.tmp` is handled the same way. Preserve it for
diagnosis and rebuild the replica from healthy peers. Do not rename it to
`snapshot.dat` manually.

## Replace a server that lost its storage

A server whose storage directory is empty starts with a new server ID. The
cluster still records the old server ID under its name and address, and that
entry still counts as a voter. A join from the new server ID is refused while
the old entry is there: only an operator can remove a member. Until then the
new server keeps asking, once a second, and takes nothing from the leader.

Use this procedure for a wiped or rebuilt directory, and as the last steps of
recovering a corrupt replica. The other servers must be healthy and hold a
quorum without the server being replaced. Every server needs the same
`server.operator.token` in its configuration; without one, removals are
refused.

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
  removal cannot commit. Preserve surviving storage and backups. There is no
  supported lost-quorum recovery procedure yet; restoring an older directory
  under an existing voter ID is not a substitute.

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
  or snapshot meant for another server ID. The replacement procedure as a
  whole has no container test yet.
- `HttpApiServerTest`: fenced liveness/readiness and corruption evidence.
- `RaftLogStorageIntegrationTest`: corruption, locking, and legacy WAL/metadata
  compatibility against the real external library.
- `FileSnapshotStoreTest` and `RaftNodeRealSnapshotRecoveryTest`: atomic snapshot
  publication, legacy snapshot reading, and first-snapshot temporary-file
  fencing.
