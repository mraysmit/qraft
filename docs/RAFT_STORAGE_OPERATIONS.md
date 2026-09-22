# Raft Storage Operations

This runbook covers the durable storage owned by a Qraft controller. Apply every
procedure to one node at a time unless the whole cluster is intentionally shut
down. A healthy quorum must remain available whenever a node is rebuilt.

## Storage layout and configuration

Set `qraft.raft.storage.type=raftlog` and point
`qraft.raft.storage.path` at a persistent, node-specific directory. In the
container deployment this is `QRAFT_RAFT_STORAGE_PATH=/app/data`, backed by a
separate named volume for each controller. Production durability requires
`qraft.raft.storage.fsync=true`.

The directory is one consistency unit:

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

Never share a node directory between controller identities or mount one writable
directory into two controller processes.

## Backup

A valid backup is a copy of the complete node directory made while that node is
stopped. Copying only `raft.log`, `meta.dat`, or `snapshot.dat` can combine
different durability boundaries and is not a valid backup.

1. Confirm the other controllers are ready and retain quorum.
2. Stop the target controller cleanly and confirm its process has exited.
3. Copy the entire configured storage directory, preserving file names,
   permissions, and bytes. For a container volume, use an offline helper or
   storage-platform snapshot only after the controller using the volume has
   stopped.
4. Record the node ID, Qraft version, time, and source storage path with the
   backup.
5. Start the controller and wait for `GET /health/ready` to return HTTP 200
   before operating on another node.

Do not copy a running node's WAL. A filesystem copy can observe `meta.dat`, the
WAL, a snapshot publication, and prefix compaction at different instants. Even
if every copied file is individually readable, their combined state is not a
supported recovery point.

To restore a backup, keep the controller stopped, preserve the current directory
for diagnosis, restore the complete backup to the same node's configured path,
fix ownership, and start the controller. Let Raft reconcile any later state from
the cluster. Never merge files from the backup and the current directory.

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
The process does not unfence in place; recovery requires a restart with a known
good storage directory.

### Recover a corrupt replica from peers

Use this procedure only when the remaining controllers are healthy, contain the
required state, and retain quorum.

1. Remove the fenced node from traffic and stop its controller process.
2. Move or snapshot its complete storage directory to a read-only evidence
   location. Preserve the original bytes and logs.
3. Create a new empty directory at the configured storage path with the correct
   owner and permissions. Do not copy selected files from the corrupt directory.
4. Start the controller with the same node identity and cluster membership.
5. Wait for it to rejoin and catch up from the leader. When its log history has
   been compacted, the leader installs a snapshot and then any WAL suffix.
6. Require HTTP 200 from `/health/ready`, `"fenced":false` from `/raft/status`,
   and matching catalog data before returning the node to service.

Recover only one replica at a time. If no healthy quorum or authoritative peer
remains, stop and restore a complete offline backup; wiping another node can make
the cluster unrecoverable.

An unpublished first `snapshot.dat.tmp` is handled the same way. Preserve it for
diagnosis and rebuild the replica from healthy peers. Do not rename it to
`snapshot.dat` manually.

## Directory-lock failures

RaftLog holds an exclusive lock for the lifetime of the storage instance. A
second JVM or container pointed at the same directory must fail startup; the
existing owner continues serving.

When this occurs:

1. Identify the process or container that owns the node directory.
2. If it is the intended controller, leave it running and correct the contender's
   storage path or volume mapping.
3. If the owner is stale, stop it cleanly and verify it has exited before
   restarting the intended controller.
4. Never bypass, delete, or replace lock state while an owner process is alive.

## Upgrade and migration

Current Qraft supports only the external `raftlog` backend. The former `file`
configuration value and RocksDB backend are not accepted production storage
types.

Legacy Qraft file-backend directories are byte-compatibility tested: the current
RaftLog reader can load legacy `meta.dat` and `raft.log`, append, replace a
suffix, sync, close, and reopen. Qraft's snapshot store can read the legacy
`snapshot.dat`; the next successful snapshot publication writes the current
versioned format.

For a file-backend upgrade:

1. Stop the node and take a complete offline backup of its directory.
2. Retain the original directory unchanged until rollback is no longer needed.
3. Configure `qraft.raft.storage.type=raftlog` and use a copied legacy directory
   as the storage path for the upgraded node.
4. Start one node, verify readiness and recovered catalog state, then allow it to
   converge before upgrading another node.
5. On any open or migration failure, stop and preserve the directory. Do not try
   alternate parsers or mutate the source in place.

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
- `HttpApiServerTest`: fenced liveness/readiness and corruption evidence.
- `RaftLogStorageIntegrationTest`: corruption, locking, and legacy WAL/metadata
  compatibility against the real external library.
- `FileSnapshotStoreTest` and `RaftNodeRealSnapshotRecoveryTest`: atomic snapshot
  publication, legacy snapshot reading, and first-snapshot temporary-file
  fencing.
