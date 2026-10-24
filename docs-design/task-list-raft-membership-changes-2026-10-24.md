# Task List: Raft Membership Changes

**Date:** 2026-09-29
**Active work:** Step 7, recovery from lost quorum. Its contract was approved
on 2026-10-22. Decision 8 records it: a single seed rebuilt offline under a
new server ID, with backup restore included. The `RaftNode` confirmation tests
passed on unchanged production code. The offline command and both container
scenarios are built and verified (2026-10-24, Jenkins build 33); the runbook
and the design update are written; Step 8 is next. Step 6,
disruptive-server validation, completed on 2026-10-21: five manual-timer
scenarios were added; after correcting the first run's incorrect expected-error
declaration, the focused rerun and five consecutive affected runs each passed
64 tests, and `mvn install` passed 1,056 tests and every coverage gate. The
retained-log audit is complete. Desktop automation is disabled
at the owner's request; verification must use the owner's VS Code terminal.
Step 5,
failed-server cleanup, completed on 2026-10-21: runtime wiring, effective policy
logging, safe heartbeat-round conversion and leader-only cleanup are implemented
and verified. Five consecutive affected runs passed 332 tests each; the clean
default build passed 1,051 tests and coverage, and the runtime gate passed 42.
The retained-log audit is complete. Step 4, joining,
and operator list and remove, is built, and its close-out gate was closed on
2026-10-11, when the owner removed the mutation rule that the gate had waited
on. Steps 1 to 3 were done
2026-09-29. Qraft adopts Consul's membership model; every decision in section
5 is made.
**Last updated:** 2026-10-24 (Step 8: scenarios 4 and 6 built and passing (leader removal five runs, cleanup after promotion four); snapshot retry backoff added test-first and checked locally, red run and Jenkins pending; scenario 3, grow 3 to 5 and shrink back while a client publishes, built and passed five consecutive runs after a test race was fixed; scenario 2, wipe and replace at the same name and address, built and passed six consecutive runs; five scenarios remain. Earlier today, Step 7: the container scenarios verified: review fixes to `DockerLostQuorumRecoveryTest` and `SharedDockerClusterFixture` (`55fbb1c`), clean `mvn install`, five consecutive focused runs, and Jenkins build 33 with 1,138 default, 10 end-to-end and 34 Docker tests; the runbook, the design section 14.5 and the feature validation record then updated, so Step 7 is complete and Step 8 is next. Earlier, 2026-10-22: Step 7: claim released when a run refuses before publishing, found in review of `885f92e` and fixed test-first; contract approved as decision 8 (single-seed recovery, whole-cluster backup restore, server-ID fence); `RaftNode` confirmation tests green on unchanged production code; offline command API stubbed in `dev.mars.qraft.server.recovery` and its 23 red tests recorded, with the `raft.lock` copy rule added to the contract; implementation next. Earlier: Step 7 contract proposed for review and the exit gate revised to match. On 2026-10-21: Step 6 completed: five consecutive affected runs passed 64 tests each, `mvn install` passed 1,056 tests and every coverage gate, and the retained-log audit of 38 logs passed; Step 7 is next. Earlier today: Step 6 corrected focused run: all 64 tests and retained-log audits passed; desktop automation disabled at the owner's request. Earlier today: Step 5 runtime policy and failed-voter cleanup completed; five repeated runs, clean build/coverage, fresh-image runtime gate and retained-log audits passed; Step 6 is next. On 2026-10-19: server wiring implemented and verified: RED build 31 retained; build 32 passed five 115-test focused runs, 1,059 default, 10 end-to-end and 32 Docker tests, coverage and the retained-log audit. The immutable local policy reaches the node, promotion uses it, effective settings log excludes secrets, and seven real-server lifecycle cases cover HTTP startup and WAL release. Automatic cleanup remains next. Earlier today, timing slice complete: promotion/quorum ceiling division and election jitter fixed, command-helper race fixed, RED builds 28 and 29 retained; replay build 30 passed five 74-test focused runs, 1,028 default tests, coverage, 42 runtime tests and the full retained-log audit. The next task at that timing checkpoint was server wiring, followed by failed-voter cleanup. On 2026-10-17: Jenkins build 27 verified the committed parser slice at `bef4637`: 1,024 default, 10 end-to-end and 32 Docker tests passed, with all coverage gates and the retained-log audit; uncommitted boundary tests and runtime work remain pending. Earlier: prepared manual-timer tests for partial-round stabilization and the largest representable stabilization duration; no build or test was run, and production is unchanged. Earlier, 2026-10-15: membership parser implemented with defaults, validation and getters; 64 focused tests passed, full verification and runtime wiring pending. Earlier: merge reconciled the 2026-10-14 archival with the newer parser tests; the focused RED run and retained output are recorded under Step 5. On 2026-10-14: single-POM cleanup list archived and its links repaired; Step 5 resumed as the active task, with implementation pending. On 2026-10-11: the mutation rule removed at the owner's decision, from the rules and from the exits of Steps 5 to 8; the Step 4 gate, which had waited on it, closed, after PIT was tried for it and put on ice; the record of RaftLog 1.5.0 in section 7; the file renamed to the date of its last change. On 2026-10-10: Step 5: what `AppConfig` became in the single-POM list's Phase 5; the file renamed with the task lists it links to. On 2026-10-09: the Step 4 gate: one more change to `RaftNode` for it to cover. Qraft's two retired words replaced, and the file renamed to the date of its last change. On 2026-10-08: review with the other task lists: the Step 4 gate after the single-POM list's Phase 3, the class name in Step 5, decisions 6 and 7 listed in section 5, and the log audit in the rules)
**Interruption completed:** [`task-list-single-pom-and-quorus-removal-2026-10-21.md`](../docs/archive/task-list-single-pom-and-quorus-removal-2026-10-21.md),
started 2026-10-04 before the Step 4 close-out gate. The gate was closed on
2026-10-11, as the first task of that list's Phase 7. That list was completed
and archived on 2026-10-14. Step 5 completed on 2026-10-21, and this list runs to its
end before the Consul-style client list starts (decided 2026-10-05).
**Predecessor:** [`task-list-test-suite-remediation-2026-10-21.md`](../docs/archive/task-list-test-suite-remediation-2026-10-21.md),
archived 2026-10-02. Its packaged-artifact test waits for the admin interface.
**Paused:** [`task-list-embedded-admin-interface-2026-10-21.md`](task-list-embedded-admin-interface-2026-10-21.md), after its Step 1
**Design:** [`QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md`](QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md), principle 8 (section 3), sections 4.2, 14.4, 14.5 and 16
**Standards:** [`PROJECT_STANDARDS.md`](../docs/PROJECT_STANDARDS.md)

## 1. Goal

Let an operator replace, add, and remove Raft servers while the cluster runs,
without losing committed data or electing two leaders. When this list is
complete:

- a server that has lost its storage cannot rejoin as the voter it used to be;
- the set of voters is changed only through the replicated log, so every
  server agrees on it;
- a new server replicates as a non-voter, and votes only once it has caught up;
- a failed server can be removed, and automatic cleanup never removes enough
  voters to lose quorum;
- a server that has been removed, or that cannot reach the leader, cannot
  disrupt a working cluster;
- the loss of quorum has a documented, tested recovery procedure.

This concerns server membership only. Clients never join the Raft membership
(design principle 8), and their registration and expiry are unchanged.

## 2. State before this list

Checked in the code on 2026-09-29, before Step 1. Steps 1 to 4 have since
changed most of it; the step records below say how. The line numbers are those
of that day's source.

- **Membership is fixed at startup.**
  - Each server parses its `clusterNodes` setting, for example
    `controller1=host1:9080,controller2=host2:9080,controller3=host3:9080`,
    into the member IDs and peer addresses
    (`QraftControllerService.java:100-113`).
  - `RaftNode` copies the set once (`RaftNode.java:349`) and never changes it.
  - A majority is `floor(n/2) + 1` of that set, for elections and commits.
- **Each server has its own copy.** Nothing makes the copies agree. Changing
  them means editing every server's configuration and restarting each one.
  During that rollout, old and new lists compute different majorities, so two
  leaders can be elected in the same term.
- **The Raft identity is the configured name.** It survives a wiped data
  directory. A server with lost storage therefore rejoins as the same voter
  with an empty term, vote, and log. It can vote twice in a term. If it had
  acknowledged a committed entry, that entry can be lost; the example is in
  section 3. Nothing detects this: there is no cluster or storage identity
  check.
- **What works.** A server that crashes and restarts with its storage intact
  reloads its term, vote, and log, and catches up. `DockerDurableRestartTest`
  and the rolling-restart test in `DockerAgentTopologyTest` cover this.
- **Not present:**
  - non-voting servers;
  - configuration entries in the log;
  - operator commands to add or remove a server;
  - failure-driven removal;
  - pre-vote or leader stickiness;
  - a procedure for recovering from lost quorum.
- `RaftNode.Builder.build()` refuses a member set that leaves the node itself
  out (added 2026-09-28). That is right while membership is fixed. Once a
  joining server can start outside the configuration, it must instead become
  "a node outside the configuration never starts an election" (Step 4).

## 3. Reference: how Consul handles this

Researched 2026-09-29 from HashiCorp's documentation and source. The sources
are listed at the end of this section.

- **Identity.** Consul identifies a Raft server by its node ID, not its address.
  This has been the default since Consul 1.0. Unless configured, the ID is
  generated at first start and kept in the data directory, so it survives
  restarts. A server rebuilt with an empty data directory gets a new ID, even
  with the same name and IP address.
- **Two layers.**
  - Gossip (Serf) tracks whether each server is alive, failed, or left.
  - Membership in Raft changes only through configuration entries in the
    log.
  - The leader reconciles the two.
- **Crash and restart.** The server keeps its node ID, stays the same Raft
  member, and catches up. HashiCorp's recovery guide calls this the best
  option.
- **Graceful leave.** The leader removes the server from the Raft
  configuration.
- **Failure.** A failed server stays a voter and still counts towards quorum
  until it is removed. Autopilot's dead-server cleanup is on by default. It
  removes failed servers when a replacement joins, and also periodically.
  - It removes at most `(voters − 1) / 2` voters, and respects `min_quorum`.
  - Without autopilot, dead servers are reaped after 72 hours.
- **A rebuilt server** (empty data directory, same address, new node ID):
  - Autopilot's `AddServer` removes the old entry at that address. It
    refuses if the removal would lose quorum.
  - It then adds the rebuilt server as a **non-voter**.
  - The server is promoted to voter only after being healthy for
    `ServerStabilizationTime` (default 10 s). Healthy means:
    - gossip reports it alive;
    - it has had leader contact within `LastContactThreshold` (200 ms);
    - its term matches the leader's;
    - it is at most `MaxTrailingLogs` (250) entries behind.
- **Lost quorum.** On the surviving servers, the operator writes
  `raft/peers.json` listing their node IDs and addresses, then restarts them.
  The guide warns that this "most likely involves data loss". A stale entry can
  be removed with `consul operator raft remove-peer`. Checked again on
  2026-10-22:
  - Each entry has `id`, the server's node ID; `address`; and an optional
    `non_voter`.
  - The server reads the file at startup and deletes it after a successful
    recovery. It refuses the file on an empty data directory.
  - One survivor is enough, and it need not have been the leader. The
    tutorial recovers a single survivor and rebuilds the lost servers with
    empty data directories, and they rejoin. With several survivors, the file
    must list all of them.
  - Listing a lost server fails with "refused to recover cluster with no
    initial state".
  - Recovery "implicitly commits all outstanding Raft log entries". Running it
    on more than one server, or on the wrong one, risks split-brain.
- **Restore from backup.** `consul snapshot restore` (`PUT /v1/snapshot`)
  loads a snapshot into a fresh, running cluster that has a leader. The
  snapshot holds key/value entries, the service catalog, prepared queries,
  sessions and ACLs. The new cluster's servers have their own identities. The
  guide prefers this to `peers.json` whenever a usable backup exists.

**Forming the first cluster** (`maybeBootstrap` in Consul's `server_serf.go`):
1. Every server has the same `bootstrap_expect = N`, and a `retry_join` list
   (addresses, DNS, or cloud auto-join tags) to find the others.
2. A server waits until it sees N servers in gossip.
3. Before bootstrapping, it checks that:
   - every server it sees reports the same expected count;
   - no server is in manual bootstrap mode;
   - no server reports existing Raft peers (a cluster already exists);
   - its own Raft log is empty. A restarted server with any Raft data
     disables bootstrapping for good.
4. It writes the initial configuration of all N servers, by node ID and
   address, as its first log entry. Every server writes the same
   configuration, and a normal election follows.
5. HashiCorp recommends removing `bootstrap_expect` once the cluster has
   formed. Later servers only `retry_join`; the leader adds them as
   non-voters.

The legacy `bootstrap` mode starts one server that elects itself, and the
others join it. At most one server per datacenter may use it.

**Why a wiped server is safe in Consul.** Its identity lived in the directory
that was wiped. It comes back as a new, non-voting member, and votes only once
caught up.

**The danger in Qraft today.** Take three servers A, B, and C:
1. The leader A commits an entry with C's acknowledgement.
2. C loses its disk and rejoins empty under the same name.
3. A crashes.
4. B, which never received the entry, wins an election with the empty C's
   vote.
5. The committed entry is lost, and B's log overwrites it.

**Raft's own guidance** (Ongaro's thesis, chapter 4):
- Change one server at a time, or use joint consensus.
- Each server uses the latest configuration in its log, committed or not.
- A leader must commit an entry in its current term before it starts a
  configuration change.
- Servers process vote and append requests without checking their
  configuration (section 4.1). So refusing votes to non-members is not the way
  to stop a removed server from disrupting elections. Pre-vote, or ignoring
  vote requests while a current leader is known (section 4.2.3), is.

Sources:
- [Consul Autopilot](https://developer.hashicorp.com/consul/docs/manage/scale/autopilot)
- [Disaster recovery for Consul clusters](https://developer.hashicorp.com/consul/tutorials/operate-consul/recovery-outage)
- [Commands: Snapshot Restore](https://developer.hashicorp.com/consul/commands/snapshot/restore)
- [Backup and restore a Consul datacenter](https://developer.hashicorp.com/consul/docs/manage/disaster-recovery/backup-restore)
- [Commands: Operator Raft](https://developer.hashicorp.com/consul/commands/operator/raft)
- [Raft Operator HTTP API](https://developer.hashicorp.com/consul/api-docs/operator/raft)
- [Node parameters (`node_id`)](https://developer.hashicorp.com/consul/docs/reference/agent/configuration-file/node)
- [Commands: Operator Autopilot](https://developer.hashicorp.com/consul/commands/operator/autopilot)
- [Consul `leader.go`](https://github.com/hashicorp/consul/blob/main/agent/consul/leader.go)
- [raft-autopilot `raft.go`](https://github.com/hashicorp/raft-autopilot/blob/master/raft.go)
  and [`reconcile.go`](https://github.com/hashicorp/raft-autopilot/blob/master/reconcile.go)
- [Bump Raft protocol version default to 3 (consul#3327)](https://github.com/hashicorp/consul/issues/3327)
- [Bootstrap a Consul datacenter (VM)](https://developer.hashicorp.com/consul/docs/deploy/server/vm/bootstrap)
- [Bootstrap parameters](https://developer.hashicorp.com/consul/docs/reference/agent/configuration-file/bootstrap)
- [Automatically join clusters to a cloud provider](https://developer.hashicorp.com/consul/docs/deploy/server/cloud-auto-join)
- [Consul `server_serf.go`](https://github.com/hashicorp/consul/blob/main/agent/consul/server_serf.go)

## 4. Rules

- Red before green. (The rule that safety guards need recorded mutations was removed by the owner on 2026-10-11, from every task list. Until then a test for
  existing behaviour that passed at once was shown to fail against a mutation
  that removed the behaviour, made by hand in an isolated copy; the records of
  Steps 1 to 3 below hold those.)
- Deterministic tests only (`PROJECT_STANDARDS.md`, section 4.4). Raft tests run
  on manual timers. Mockito is prohibited.
- Every safety rule is tested at the unit level on `RaftNode`, and again in
  containers when it depends on process restarts or real storage.
- A new or changed concurrency test is run repeatedly before its step is done.
- A step is verified with `mvn install`, which runs the coverage gates. When
  the runtime changes, the Docker suite then runs on a freshly built image.
- The design document is updated in the same step as the behaviour it
  describes.
- Every new Java file carries the licence header and attributed type Javadoc.
- A run is accepted only after its retained Maven, application, subprocess,
  and Docker logs have been read and hold no unflagged error (`AGENTS.md`, and
  `docs/TESTING.md`, "Intentional error flags and log auditing"). A test that
  causes an error on purpose declares it. Added 2026-10-08: the rules above
  and the exit gates below predate the logging policy, and it applies to each
  of them.
- No commits; the user commits.

## 5. Decisions

**Decided 2026-09-29: Qraft adopts Consul's membership model.**
- The server configuration is used once, to form the cluster.
- From then on, the membership lives in the replicated log.
- Each server is identified by a node ID kept in its data directory.
- New servers join as non-voters and are promoted once caught up.
- Failed servers are removed automatically, within quorum limits.

Decisions 1, 2, 4, and 5 follow from this. Decision 3 was made the same day.

1. **Change method. Decided:** single-server changes, as in Consul's Raft
   library and Raft's thesis. Replacing, adding, or removing is done one
   server at a time.
2. **Identity and addressing. Decided:**
   - A node ID is generated into the data directory, as in Consul. The
     configured name stays as the display name.
   - Peers are addressed through the configuration held in the log.
   - `server.raft.nodes` (`clusterNodes` in code) stops being the membership.
     It becomes the list of servers to contact when bootstrapping or joining.
   - In code the generated ID is called the **server ID**, because `nodeId`
     already means the configured name throughout Qraft.
   - A data directory from before Step 1 already holds a WAL, but no server
     ID. It gets a new server ID at its next start. The storage is the same,
     so it is the same server.
   - A server ID file that exists but cannot be read stops the server from
     starting. It is never replaced silently, because that would change the
     server's identity.
3. **Failure detection. Decided 2026-09-29:** the leader's own contact
   tracking, which check-quorum already keeps. It needs no new protocol, and
   only the leader removes servers.
   - Qraft does not add gossip.
   - Where Consul relies on gossip, Qraft uses direct contact instead:
     bootstrapping exchanges facts with the seeds over the server port, and
     the health criteria use the leader's contact record.
4. **Automatic cleanup. Decided 2026-09-29:** failed servers are removed
   automatically, as in Consul, within the quorum limits of Step 5.
5. **Bootstrapping. Decided:** like Consul's `bootstrap_expect`, with
   `server.raft.nodes` as the servers to contact and its size as the expected
   count (refined in the Step 2 plan). A server bootstraps only when all of
   these hold:
   - every listed server answers;
   - they all report the same list;
   - none of them already has a cluster;
   - it has no Raft state of its own.

   Servers exchange these facts with their seeds over the existing server
   port (decision 3). The "none already has a cluster" check also stops a
   wiped server from bootstrapping a second cluster.
6. **Protecting changes. Decided 2026-09-29** for Step 4, and recorded
   there: a removal needs the operator token, and listing stays open.
7. **Forwarding. Decided 2026-09-29** for Step 4, and recorded there: any
   server forwards a join or a removal to the leader, once.
8. **Lost-quorum recovery. Decided 2026-10-22** for Step 7, whose contract
   is recorded there. Design section 22 requires an architecture decision
   record for a change to durable identity; this entry is that record.
   - The nearest Consul equivalent is its single-survivor `peers.json`
     recovery (section 3). Qraft departs from it by never editing the
     survivor's directory and by giving the seed a new server ID. Step 7
     compares the two in detail.
   - One stopped survivor, or an offline backup, becomes the **seed**. An
     offline command copies it into a new data directory and appends one
     recovery configuration entry. That entry makes the seed the sole voter
     under a new server ID. The source is never changed.
   - Every other directory is excluded. Other survivors are not recovered in
     place: they are wiped and rejoin through the existing join and promotion
     path, under new server IDs.
   - Whole-cluster restore from an older backup uses the same command and
     has its own scenario.
   - The old cluster is kept out by server IDs, with no wire or format change:
     - The existing target-server-ID and configured-ID checks refuse traffic
       between the seed's new identity and the old configuration.
     - Leader stickiness ignores campaigns while a leader is live.
     - The runbook's mandatory quarantine keeps excluded directories from
       starting.
   - The recovery entry is the only configuration entry exempt from the change
     rules of design section 4.2: one server at a time, new servers as
     non-voters, and a change only after a current-term commit. It is written
     offline and never submitted to a running cluster.
   - Rejected alternatives:
     - Consul-style multi-survivor `peers.json`. It needs identical inputs on
       every survivor, reconciliation between survivors and a separate fence.
     - A recovery epoch carried in every RPC. It is a wire and
       durable-format change.

## 6. Steps

### Step 1. Durable server identity

This step lays the foundation. It does not close the wiped-server danger on
its own; Step 2 does.
- A peer could remember each member's server ID and refuse a changed one. That
  only protects servers that saw the old ID.
- In the example in section 3, B may never have talked to C. It would accept
  the wiped C's new ID and count its vote.
- The IDs of the voters must be known to every server. That means they live in
  the replicated configuration, which is Step 2.

The work:
- At first start, generate a server ID (a UUID) and keep it in `server-id` in
  the Raft data directory, next to the WAL and snapshots.
  - Write the file atomically, and make it durable before the server serves
    any request.
  - Reload the same ID on every later start.
  - A missing file after a wipe gives a new ID.
  - A file that exists but cannot be read stops the start (decision 2).
- `RaftNode` carries its server ID. Every Raft request and response carries
  the sender's server ID, ready for Step 2:
  - vote requests and responses;
  - append requests and responses;
  - snapshot-install requests and responses.
- The server reports its server ID in `/raft/status` and its startup log.
- Tests:
  - creation, reload, a new ID after a wipe, refusal of an unreadable file,
    and no temporary file left behind;
  - every message a node sends carries its ID;
  - the ID survives a container restart.

### Step 1 record (2026-09-29)

**Built:**
- **`ServerIdentity`** (`raft.storage`) creates or reloads `server-id` in the
  Raft data directory.
  - The file is written to a temporary file, forced to disk, atomically
    moved into place, and the directory is then forced.
  - An unreadable file stops the start, and the message names the file.
- **The controller service** loads the ID once the WAL is open, and so holds
  the directory's lock. It logs the ID and builds the node with it.
- **`RaftNode.Builder.serverId`** sets the node's ID. The default is a fresh
  UUID, for nodes whose storage does not outlive them.
- **`getServerId()` and `RaftStatus.serverId`** expose the ID, and
  `/raft/status` reports it.
- **New protobuf fields:** `candidate_server_id`, `voter_server_id`,
  `leader_server_id` (append and snapshot install), and `follower_server_id`
  (append and snapshot install). They use new field numbers, so older
  messages still parse.
- **Stamping.** Requests are stamped where they are built.
  - The vote response has one build site.
  - The append and snapshot handlers stamp their single returned future, so
    every path is covered, refusals included.

**Tests:**
- `ServerIdentityTest`: creation, reload, a new ID after a wipe, a data
  directory from before server IDs, refusal of an unreadable file, and
  recovery from an interrupted first start.
- `RaftNodeServerIdentityTest`:
  - the ID a node was built with, and a default UUID;
  - a three-node scenario in which every vote, append, and snapshot-install
    request, and every response, names its sender;
  - refusals naming the responder.
- `/raft/status` in `HttpApiServerTest`.
- In `UnifiedRuntimeEndToEndTest`, the server reports the ID from its data
  directory, and a server started on new storage reports a different one.
- In `DockerDurableRestartTest`, three distinct IDs survive a whole-cluster
  restart.

**Evidence:**
- Every new test was red before its code existed.
- Ten mutations were caught:
  - three in `ServerIdentity`;
  - all six stamps;
  - the controller building its node without the loaded ID.
- `mvn install`: 715 tests, every coverage gate met.
- The new tests passed 5 repeated runs.
- The Docker suite, on an image built from the new JAR: 22 of 22.

**Not yet:** nothing counts votes or acknowledgements by server ID. That is
Step 2.

### Step 2. Configuration entries in the log

- Add a configuration entry type holding the voters and non-voters, each with
  server ID, name, and address. Each server uses the latest configuration in
  its log, committed or not, for elections, commits, and replication targets.
- A vote or an acknowledgement counts only when its sender's server ID is a
  voter in that configuration. This closes the wiped-server danger: a server
  that lost its storage comes back with a new ID, so it cannot vote or be
  counted towards a commit until it is added as a new member.
  - Container test: wipe one server's data directory, restart it, and show
    that it is not counted towards any election or commit.
- Snapshots record the configuration they cover, and recovery restores it from
  the snapshot and the WAL.
- Truncating an uncommitted configuration entry reverts to the previous
  configuration.
- The leader starts a change only after it has committed an entry in its
  current term, and only one change is in flight at a time.
- **Bootstrapping** (decision 5). A server with no Raft state contacts the
  servers in `server.raft.nodes`. It writes the initial configuration only
  when it has reached the expected count and all the checks in decision 5
  pass. A server with any Raft state never bootstraps. A server that finds an
  existing cluster joins it instead.
- Unit tests on manual timers:
  - bootstrapping only at the expected count, and never with disagreeing
    counts, an existing cluster, or local Raft state;
  - majorities under each configuration;
  - reverting a truncated change;
  - recovery from a snapshot and from the WAL;
  - refusing a second change while one is pending.

### Step 2 plan (2026-09-29)

Two further decisions, taken on the recommendations:
- **No in-place upgrade of data from before Step 2.** A data directory that
  holds Raft state but no configuration stops the server, with a message
  saying so. Qraft has no deployments to migrate. Deriving a configuration
  from `server.raft.nodes` would bring back the name-based trust this step
  removes.
- **Decision 5 refined: the expected count is the size of
  `server.raft.nodes`, with no separate setting.**
  - If two servers bootstrapped with different configurations, each would
    write a different entry at index 1 in the same term. Raft's log matching
    rule would then treat the two entries as one.
  - An expected count smaller than the list, or lists that differ between
    servers, allow that.
  - So a server bootstraps only when every server in its list answers, has no
    Raft state, and reports the same list.
  - The configuration is built from each server's own report of its server
    ID and address, so every bootstrapping server writes the identical entry.
- **The bootstrap entry.** It is written at index 1 with term 0, so no term
  is spent, and is treated as committed. Any server that holds an entry at
  index 1 wrote this identical entry, or received it from a leader. A server
  with no configuration cannot lead, so no other entry can take index 1.

Slices, each red before green:
- **2a. Values.**
  - `RaftConfiguration`: servers with ID, name, address, and voter flag.
  - A configuration log entry, carried by the command codec and never
    applied to the state machine.
  - A snapshot envelope that records the configuration a snapshot covers.
- **2b. `RaftNode` tracks its configuration.**
  - The latest configuration comes from the log, or else the snapshot.
  - Truncation reverts to the previous configuration.
  - Recovery restores it from the WAL and the snapshot.
  - A node with no state can be bootstrapped with an initial configuration.
    A single-member node bootstraps itself.
  - Raft state without a configuration refuses to start.
- **2c. Counting by server ID.**
  - Elections, commits, replication targets, and check-quorum use the
    voters of the latest configuration.
  - A vote or acknowledgement counts only when its sender's server ID is the
    configured voter's.
  - A node that is not a voter in its configuration never campaigns.
  - The gRPC transport keeps its addresses from `server.raft.nodes`. Every
    configured server is listed there until Step 3 adds servers, and taking
    addresses from the configuration moves there.
  - The test suite's simulated peers stamp their server IDs.
- **2d. Bootstrapping a server.**
  - A new `Describe` RPC on the server port reports server ID, name,
    address, listed servers, and whether the server has Raft state.
  - The controller bootstraps under decision 5's checks, or waits for the
    configuration to be replicated to it.
- **2e. Configuration changes.** The leader proposes a change only after it
  has committed an entry in its current term, and only one change is in
  flight at a time. Steps 3 and 4 use this.
- **2f. Containers and docs.**
  - Form a cluster from empty storage.
  - A wiped server is counted towards no election or commit.
  - Update the design document.

### Step 2 record (2026-09-29)

**Built:**
- **2a. Values.**
  - `RaftConfiguration`, in name order, with a quorum of voters.
  - `ConfigurationCommand` and its codec: a stored configuration is rebuilt
    through its constructor, so one that breaks the rules is refused.
  - `SnapshotEnvelope`, which puts the configuration before the state
    machine's bytes and refuses data from before configurations were
    recorded.
- **2b. The node tracks its configuration.**
  - The configuration in force is the latest in the log, or else the
    snapshot's.
  - Truncation reverts to the one before. Compaction keeps the configuration
    in force.
  - Recovery restores it from the WAL or the snapshot, and an installed
    snapshot brings its own.
  - A node with no state bootstraps at index 1, in term 0, as committed. On
    recovery, the bootstrap entry is committed again.
  - A node holding Raft state but no configuration refuses to start.
- **2c. Counting by server ID.**
  - Votes, acknowledgements, and check-quorum count only configured voters,
    by server ID. A reply from another ID is ignored, term included.
  - Commits count only voters.
  - A server that is not a voter never campaigns.
- **2d. Bootstrapping.**
  - A `Describe` RPC reports a server's ID, name, address, listed servers,
    and whether it holds state.
  - `RaftNode.bootstrap` works only while the node holds no state, checked
    inside its transition.
  - `ClusterBootstrap` decides by decision 5's rules.
  - The controller retries every second until the node is configured, and
    stops at shutdown.
- **2e. Changes.** `RaftNode.proposeConfiguration` makes single-server
  changes, and only after a commit in the leader's term, with no other change
  in flight. The rules apply to any configuration entry. An added server is
  tracked at once, but earns check-quorum credit only by answering.
- **2f.** A container test covers the wiped server, and the design document
  is updated.

**Defects found and fixed on the way, each test first:**
- **A follower replaced committed entries.** It accepted an append that
  replaced an entry at or below its commit index, and silently never applied
  the replacement. Such an append is now refused. Two migration agents found
  this independently.
- **Index 1 accepted any entry.** An unconfigured follower took an ordinary
  command at index 1, and then held Raft state with no configuration. Index 1
  must now hold a term-0 configuration.
- **A late reply moved a follower back.** A reply to an earlier, shorter
  append could arrive last and lower the leader's match index for that
  follower, stalling commits until the entry was sent again. The match index
  now only rises within a term. The four-voter check-quorum test found this
  as an intermittent failure.
- **A removed server stayed tracked** (found in the code review of this
  step). The leader's per-peer next index, match index, contact round, and
  unavailability were keyed by name and never dropped, so a server added
  later under the same name, such as a wiped server's replacement, inherited
  its predecessor's values. Its stale match index could not commit anything
  unsafe: the removal was committed by a majority already past it, and the
  commit index never moves back. But the replacement was credited with
  contact it never made, and was sent entries from a next index it could not
  accept. Tracking now follows the configuration in force.

**Test migration.**
- About 108 existing tests assumed the old model. Four agents migrated them
  in parallel under written rules: index shifts for the bootstrap entry,
  bootstrap fixtures, envelope snapshots, and server-ID stamps on simulated
  peers. I reviewed their reports and a sample of the diffs.
- `ManualRaftCluster.builder` now gives each node the ID `serverIdOf(name)`
  and a configuration of its members. `unconfiguredBuilder` is the old
  behaviour.
- Several tests that had passed without testing anything now exercise what
  they claim. For example, the timer test's stale election could never have
  raised the term on a node that never campaigns.

**Evidence:**
- Every new test was red before its code, or shown to fail against a
  mutation. 39 in-process mutations were caught: 10 in 2a, 15 in 2b and 2c,
  3 on the two log guards, 6 in 2d, and 5 in 2e. There is also one Docker
  mutation (see below).
- The changed leader tests passed 5 repeated runs.
- `mvn install`: 755 tests, every coverage gate met.
- The Docker suite, on an image built from the final code: 23 of 23. Its
  three-server clusters are formed by `Describe` and bootstrap.
- The wiped-server container test fails against an image in which the
  leader listens to replies from an unconfigured server ID. In that image,
  the leader and the wiped server committed a write together.

### Step 3. Non-voters and promotion

- A non-voter receives replication but is not counted for elections or commits,
  and never starts an election.
- Adding a server adds it as a non-voter. It is promoted when all of these hold:
  - it has had recent leader contact;
  - its term matches the leader's;
  - it is within a configured number of entries of the leader's log;
  - it has stayed healthy for a stabilization period.
- The criteria and their defaults are recorded in the design document.

### Step 3 record (2026-09-29)

**Built:**
- **Joining.** A configuration change that adds a server as a voter is
  refused: a server joins as a non-voter. Changing an existing non-voter to a
  voter is allowed. A non-voter already never campaigned (Step 2) and is not
  counted.
- **Health.** At each heartbeat round, the leader judges each non-voter
  healthy when:
  - it has answered this leadership within the last 2 heartbeat rounds, and
    has answered at all. The contact round a new peer starts with is only a
    placeholder, so it does not count. Only replies to the current leadership
    are recorded, which covers "its term matches the leader's";
  - its match index is at least the leader's last index less
    `promotionMaxTrailingEntries` (default 250).
- **Promotion.** Once a non-voter has been healthy for
  `promotionStabilization` (default 10 s, rounded up to heartbeat rounds),
  the leader proposes the configuration with it as a voter. A lapse restarts
  the period, and a new leader starts afresh. It promotes one server per
  round; a refused promotion, such as one made while another change is
  uncommitted, is retried at the next round.
- **Builder options:** `promotionStabilization(ms)` and
  `promotionMaxTrailingEntries(n)`. They are not yet exposed in the server
  configuration, and the operator commands of Step 4 are where a server gets
  added.
- **Test support.** `HeldRaftTransport` is extracted from
  `RaftNodeServerIdCountingTest`. It holds every request for the test to
  answer, so `RaftNodePromotionTest` counts heartbeat rounds exactly.

**Tests:**
- `RaftNodePromotionTest`, with 6 tests: joining as a non-voter; promotion at
  exactly the round the stabilization period ends; the trailing-entries
  boundary (two behind is refused, one behind with the limit at one is
  promoted); answering every other round stays in contact; answering every
  third round never stabilizes; a server that never answers is not promoted.
- `RaftNodeConfigurationChangeTest`: the voter test now adds d as a
  non-voter and waits for its promotion before showing that d counts towards
  the quorum. Two replication tests now compare the added server's
  configuration with the leader's, since the server is promoted once it
  catches up. One of them checked the commit index after the wait instead of
  in it, which became a race once promotion added an entry. It is now part of
  the wait condition.

**Evidence:**
- The new tests were red before the code (the builder options were added
  first, so the tests failed on behaviour, not compilation).
- 10 in-process mutations were caught:
  - the voter-add refusal removed;
  - the answered check removed;
  - the contact-recency check removed;
  - the contact threshold set to 1, and to 3;
  - the trailing check made strict, and removed;
  - the stabilization check made strict;
  - the health reset removed;
  - promotion never run.
- The changed tests passed 5 repeated runs.
- `mvn install`: 762 tests, every coverage gate met.
- The Docker suite, on an image built from the final code: 23 of 23. Its
  clusters bootstrap with every server a voter, so promotion is not exercised
  there; Step 8's container scenarios will add a server.

### Step 4. Joining, and operator list and remove

Revised 2026-09-29 to follow Consul more closely (decisions 6 and 7 below).
It was "Operator add and remove".

- A server with no Raft state that finds an existing cluster asks to join,
  as Consul's `retry_join` does. Any server forwards the request to the
  leader, which adds it as a non-voter; Step 3 promotes it. There is no
  operator add command, as in Consul.
- A server that rejoins under a new server ID, after losing its storage,
  is refused if its name or address belongs to an existing member. An
  operator must remove the old entry with the operator token (within the
  quorum rule below), after which the new server can join as a non-voter.
- Server API and CLI commands to list the Raft configuration and remove a
  server. Removal needs the operator token (decision 6); any server forwards
  it to the leader (decision 7).
- A removal that would leave fewer voters than a quorum can survive is
  refused: the voters left, among those the leader has heard from recently,
  must still be a quorum.
- A leader that removes itself steps down after the change commits.
- A node outside the configuration never starts an election (true since
  Step 2). The builder check that a server's list includes itself stays: the
  list is where a server learns its own address.
- The Raft transport reaches servers at the addresses in the configuration,
  not only those in `server.raft.nodes`, so a joined server is reachable.

**Decisions made 2026-09-29 for this step:**

6. **Protecting changes.** Consul protects its operator endpoints with ACLs
   (`operator:read` to list, `operator:write` to remove), and with its HTTP
   API bound to `127.0.0.1` by default. Qraft has no ACLs yet, and its HTTP
   API listens on all interfaces. So a removal needs the operator token
   (`server.operator.token`), sent as `X-Qraft-Token` or `Authorization:
   Bearer`. With no token configured, removals are refused. Listing stays
   open, like `/raft/status`. A forwarded removal carries the token, and the
   leader checks it too.
7. **Forwarding.** As in Consul, any server forwards a join or a removal to
   the leader over the Raft port, and returns the leader's answer. A
   forwarded request is never forwarded again.

**Review fixes (2026-10-01),** from the code review of commit `05a2055`.
Written test first. They were not run that day; they passed in the full-suite
run of 2026-10-02, recorded below:
- A forward to a leader the transport cannot address failed by throwing,
  which escaped both servers. The transport now fails the future, and
  forwarding answers `NO_LEADER`.
- A joining server asked only the first listed member with state, so a
  member that took no requests, such as a leader that removed itself,
  stalled it for ever. It now asks each member in turn.
- A leader that removed itself idled unnoticed. It now reports `removed` in
  `/raft/status` and on `/health/ready`.
- A known server at a new address was left at its old one. Its address is
  now updated, and it keeps its vote.

### Step 4 record (2026-10-02)

The code and its tests are in commits `05a2055` (2026-09-29) and `d081c7a`
(2026-10-01). The step is not closed: see "Not yet" below.

**Built:**
- **`RaftNode`** admits a joining server as a non-voter, refuses collisions
  with another server ID until an operator removes the old entry, updates a known server's
  address, and removes a server within the quorum rule. Only a leader admits
  or removes. A leader that removes itself steps down once the change commits
  and reports `removed`.
- **`MembershipService`** takes a join or a removal on any server, checks the
  operator token for a removal, and forwards to the leader once. `JoinResult`
  carries the answer.
- **Transport.** `raft.proto`, `GrpcRaftServer` and `GrpcRaftTransport` carry
  the join and removal requests over the Raft port. The transport reaches
  servers at the addresses in the configuration.
- **`ClusterBootstrap`** asks each listed member with state, in turn, to add
  the server.
- **HTTP.** `HttpApiServer` serves `GET /v1/operator/raft/configuration` and
  `DELETE /v1/operator/raft/peer` (design section 12.5). `/raft/status` and
  `/health/ready` report `removed`.
- **Configuration.** `server.operator.token`, at least 16 characters (design
  section 16).
- **CLI.** `RaftOperatorCommand` in `qraft-runtime`: `qraft operator raft
  list-peers` and `remove-peer`.
- **Documentation.** Design sections 4.2, 6.1, 12.5, 16 and 18, and
  `docs/TESTING.md`.

**Tests:**
- `RaftNodeMembershipTest`, 16 tests after the review remediation: joining
  as a non-voter, an address update, a colliding join refused until an
  operator removes the old entry, changes on an idle cluster, leader
  stickiness, the quorum rule on removal, and a leader removing itself.
- `MembershipServiceTest`, 14 tests: forwarding, invalid joins refused before
  forwarding, the token checked on both servers, and each answer.
- `HttpApiServerOperatorTest`, 7 tests: listing, removal, and each status
  code.
- `RaftOperatorCommandTest`, 7 tests: both commands, token handling, and exit
  codes.
- Added to existing classes: `ClusterBootstrapTest` (asking each member in
  turn), `GrpcRaftTransportTest` (an unaddressable server fails the future),
  `GrpcRaftIntegrationTest`, `AppConfigValidationTest`, and
  `HttpApiServerReadinessTest` (a removed server is live but not ready).

**Not yet:**
- Mutation validation for this step remains outstanding. The remediation
  build, the focused regression runs, and the full-suite run of 2026-10-02 are
  recorded below.
- `promotionStabilization` and `promotionMaxTrailingEntries` are still builder
  options only, not server configuration (Step 3 record).
- No container test adds or removes a server; that is Step 8.

### Review remediation (2026-10-02)

The review of `1f74a77` through `d081c7a` identified four issues:

- **Idle membership changes (Step 4): fixed.** Every elected leader appends
  a durable current-term no-op. Membership no longer depends on a client
  write. While that no-op awaits a quorum, the membership service answers
  retryable `NO_LEADER` rather than terminal `REFUSED`.
- **Removed-server disruption (Step 6): fixed with leader stickiness.**
  A leader rejects campaigns while it leads; check-quorum still releases
  an isolated leader. Followers reject votes, without adopting higher
  terms, for the minimum election timeout after leader contact. The lease
  uses monotonic time, and expires so genuine failover can proceed.
- **Join-driven eviction and blank IDs (Step 4): fixed.** Server records
  validate ID, name and address before admission or forwarding. A join
  cannot remove an existing server ID through a name/address collision;
  replacement now requires authenticated operator removal. The Raft port
  remains trusted, including claims to an existing server ID.
- **Moved stored members (Step 4): fixed.** Startup reconciliation runs
  even when configuration is recovered. A member with an old self-address
  retries joining until the advertised address is replicated; a moved
  leader updates its own address. Removed members do not re-admit themselves.

Regression coverage includes idle admission/removal and automatic promotion,
repeated higher-term removed-server requests, the exact follower contact
expiry boundary, malformed joins and collisions, and a moved member
recovering its identity and configuration from the real WAL. Existing
durability, snapshot, model and transport tests account for leadership
no-ops; failover fixtures share a monotonic clock.

**Validation:** `mvn install -Dmaven.test.redirectTestOutputToFile=true`
passed on JDK 27, with all reactor modules and coverage gates passing. A
subsequent focused run passed 52 tests, including the additional readiness
retry regression. After fixing a learner fixture's race with the new no-op,
the expanded 60-test selection passed three consecutive runs. Docker and
mutation suites were not run for this remediation.
Step 4 remains open for the other items listed above; Step 6's container
scenarios and broader partition testing remain outstanding.

### Requests name their target server (2026-10-02)

Found in the review of the remediation above.

- **Defect.** Until an operator removes a wiped server's old entry, the
  leader keeps sending to that entry at the wiped server's address. Its
  appends fail the log check, because the leader ignores the wiped server's
  replies and never moves back to index 1. A snapshot install has no log
  check. Once the leader had compacted past the old entry's next index, the
  wiped server would install the snapshot. It then held a configuration that
  left it out, reported `removed`, and stopped asking to join, at every
  restart too.
- **Fix.** `AppendEntriesRequest` and `InstallSnapshotRequest` carry
  `target_server_id`, the server ID the configuration records for the peer.
  A server refuses a request that names another server ID, before its term,
  log, snapshot, or leader changes. A request that names no server is taken
  as before. Vote requests are unchanged: a vote from a server ID outside the
  configuration was already ignored.
- **Tests,** in `RaftNodeServerIdentityTest`, both red before the fix:
  - every append and snapshot install the leader sends names its target;
  - a request meant for another server ID is refused and changes nothing, and
    the same request under the server's own ID is accepted.
- **A test I wrote and deleted.** A membership test of a wiped server beside
  a live leader passed its log assertions without the fix, for the reason
  given above, so it could not fail for the defect.
- **Chaos test.** `EnhancedInMemoryTransportTest`'s convergence helper fired
  the election timeout of the member with the highest term, "which it wins
  because every log is empty". With a no-op in every leadership, that member
  can hold a log that is behind and stand for ever. The first `mvn install`
  failed there. The helper now heartbeats a leader if there is one, and
  otherwise stands the member with the most up-to-date log.
- **Validation.** The changed classes passed 5 consecutive runs (28 tests).
  `mvn install` then passed: 812 tests, every coverage gate met. The Docker
  and mutation suites were not run.
- **Runbook.** `docs/RAFT_STORAGE_OPERATIONS.md` has the operator procedure
  for replacing a server that lost its storage: remove the old entry, then
  start the empty server. Its corrupt-replica procedure, which still said the
  server rejoins by itself, now points there. The procedure has no container
  test; that is Step 8.

### Full-suite run (2026-10-02)

Run on the working tree that holds the review remediation, the target server
ID, and the chaos-test fix, on JDK 27:

- `mvn install`: 812 tests, no failures, every coverage gate met.
- The Docker suite, on an image built from that install's JAR: 23 of 23.
- The end-to-end suite: 7 of 7.

Still outstanding: mutation evidence for the stickiness, collision, moved
server, and target server ID tests. No container test yet adds, removes, or
replaces a server; that is Step 8.

### Step 4 close-out gate

Before proceeding to Step 5, record mutation evidence for joining/removal,
forwarding and token checks, collision protection, the removal quorum guard,
idle-cluster changes, moved stored members, and target server IDs. Include the
leader-stickiness guards shared with Step 6. Re-run any changed concurrency
tests five consecutive times, then the default, end-to-end, and fresh-image
Docker suites if production or test code changes. If only mutations are run
in the isolated copy, retain the mutation commands/results and identify the
unchanged final source/dependency state covered by the existing full-suite
evidence. Passing ordinary suites alone does not close the outstanding gate.

**Scheduled (2026-10-05).** This gate is the first task of Phase 7 in
[`task-list-single-pom-and-quorus-removal-2026-10-21.md`](../docs/archive/task-list-single-pom-and-quorus-removal-2026-10-21.md).
By then the source has its final packages and its tests declare their
intentional errors, and Phase 7 has not yet changed `RaftNode`'s async code.

**Changed by that list's Phase 3 (review of 2026-10-08).** Phase 3 changed
`RaftNode.becomeLeader`, which two of the guards above depend on:
- the leadership no-op is appended, and the replication state initialized,
  before leadership is published. Idle-cluster changes rely on that no-op;
- leadership is declined once a stop has been requested.

Add a mutation of each to this gate's evidence. The full-suite run of
2026-10-02 no longer describes the source. The run that stands behind the
mutations is the one that closes the single-POM list's Phase 6.

**Changed by that list's review of Phase 4 (2026-10-09).** A server that does
not campaign, because it holds no configuration yet or is still a non-voter,
used to keep the leader it knew for as long as it was cut off, and so reported
itself ready. `RaftNode` now makes it forget a leader that has been silent for
an election timeout (`forgetSilentLeader`); the next message from a leader
makes the leader known again. It was written test first, in
`RaftNodeServerIdCountingTest`. Add a mutation of it to this gate's evidence.

**Attempted with PIT, and PIT put on ice (2026-10-11).** The single-POM list's
Phase 7 has the whole record. In short:
- PIT was configured for `RaftNode`, `MembershipService`, and
  `ClusterBootstrap`. Its dry runs passed: 1,187 mutants, and the default
  suite covers 92% of the lines of those classes.
- The full analysis was aborted after two hours with no report. A mutant that
  removes a step from Raft code often makes a test wait, not fail, and the run
  would have taken days.
- PIT was then cut down to `MembershipService` alone, 39 mutants in five
  batches of at most ten. No batch was run.
- The owner put PIT on ice that evening: "I see zero value at the moment ...
  The test coverage in QRaft is excellent which makes me wonder why pit in
  needed at all."

**Closed 2026-10-11, at the owner's decision.** The owner removed the mutation
rule entirely that evening, from every task list, so this gate asks for
nothing that is still required:
- its mutation evidence is no longer a requirement, and none was produced;
- its other condition, re-running the changed concurrency tests and the three
  suites if production or test code changes, does not apply: the gate changed
  no code.

What stands behind Step 4 is what its records above describe: the tests
written with it, red before green where a defect was fixed, and the suites
that have passed on the source since, the last of them Jenkins build 21 on
`e3cf808`, with 969 results and none failed. Step 5 resumed on 2026-10-14 after the
single-POM list was completed and archived, as recorded at the head of this list.

### Step 5. Failed-server cleanup

**Status: Complete, 2026-10-21; all Step 5 verification gates passed.**
The contract below was specified during the document review on 2026-10-03.
`AppConfig` accepts and validates the membership settings. `QraftServerService`
wires all six values into `RaftMembershipPolicy`, and the node logs the effective
policy and its safely rounded heartbeat durations. Automatic cleanup is
implemented and verified. The expanded real replacement and cleanup container
matrix remains mandatory in Step 8 before the overall membership list closes.

**Parser-test record, 2026-10-15.** The cleanup archival and membership
resumption were committed on 2026-10-14 in `217e28c`. Local commit `88457d1`
added `AppConfigRaftMembershipTest` as the first implementation slice. The
merge preserves the original archival date and these newer tests.

The tests specify parser acceptance and rejection through the existing
`AppConfig.fromJson` API: empty and non-default settings, zero promotion
limits, cleanup timing boundaries, large representable duration inputs,
malformed types and ranges, unknown fields and duplicate keys.

At the owner's request, the focused Maven command ran in the visible VS Code
integrated terminal with combined-output capture and an immediate exit-code
check. RED: 61 tests, 59 failures, zero errors or skips; Maven exit code 1.
All 59 failures trace to `Unknown server.raft setting: membership`, the
missing parser behavior. The two passing tests cover absent membership and
duplicate JSON keys. Retained output:
`logs/qraft-membership-red-2026-10-15_17-16-26-101.log`; the accompanying
application log, `logs/qraft-maven-tests-2026-10-15_17-16-56.log`, is empty.
This is a failing baseline, not a successful verification run. No helper
subprocess or Docker suite was run. Production code was unchanged for this
RED run; the parser implementation follows below.

**Parser implementation, 2026-10-15.** `AppConfig` stores membership and
cleanup settings in immutable records, reads them through the existing
`JsonSettings` helpers and exposes all six values through getters. It rejects
unknown fields, malformed types (including explicit null objects), negative
promotion settings, nonpositive cleanup settings and cleanup durations below
their configured Raft timing bounds. Disabling cleanup does not bypass
validation. Omitted values retain the documented defaults; larger election
or heartbeat intervals increase the corresponding cleanup defaults. Parsing
preserves representable millisecond values without converting them to rounds;
the node wiring must use overflow-safe ceiling division.

GREEN: all 64 focused `AppConfigRaftMembershipTest` cases passed, with zero
failures, errors or skips and Maven exit code 0. The expanded assertions check
all getters, defaults, partial settings, timing boundaries, disabled cleanup
and the largest representable values. Retained output:
`logs/qraft-membership-parser-green-2026-10-15_18-12-22-126.log`; application
log `logs/qraft-maven-tests-2026-10-15_18-12-52.log` is empty. The retained
capture has no ERROR entry, exception trace or ANSI escape. JaCoCo reported
execution-data/class mismatches from earlier builds; this focused test result
does not establish coverage. A clean default build and the runtime suites
remain pending; no full-suite result is claimed. Lifecycle wiring, effective
settings logging, overflow-safe round conversion and automatic cleanup follow
in the next slice. At that date the overall Step 5 exit gate remained open.

**Runtime implementation and verification, 2026-10-21.** A clean baseline on
`bef4637` passed 1,024 tests and every coverage gate. Its capture is
`logs/qraft-membership-baseline-2026-10-21_11-47-15-990.log`. The audit of its
25 retained Maven/application/helper logs found 337 flagged ERROR headers and
288 exception headers with no unflagged header or ANSI escape. These are counts
including duplicate captures. The earlier baseline,
`qraft-membership-baseline-2026-10-21_11-44-36-970.log`, failed test compilation:
main classes disappeared while VS Code was also rebuilding `target/classes`.
Java auto-build was disabled in local `.vscode/settings.json` before the clean
rerun; no compiler or generated-source error was excused as test noise.

The first new behavioral RED run,
`qraft-cleanup-red-2026-10-21_11-50-37-689.log`, showed missing default cleanup
and overflow into immediate promotion, plus a timeout in the existing promotion
test helper. That helper chose a command index before the leadership no-op had
been appended and could acknowledge the wrong request. It now awaits a sequenced
barrier after leadership. The corrected RED run,
`qraft-cleanup-red-2026-10-21_11-51-59-210.log`, had exactly those two assertion
failures among eight tests, with no error or skip.

`RaftMembershipPolicy` holds the local settings; node construction validates
timing and uses `Math.ceilDiv` for promotion, cleanup and check-quorum durations.
`RaftNode` tracks continuous absence by durable server ID, resets it per
leadership, and freezes eligible IDs and the committed voter budget per pass.
Each proposal rechecks its leadership and configuration revision, eligibility,
both quorums, minimum and the existing configuration-change guards inside the
sequencer. Only commitment schedules the next proposal. An unrelated change,
lost leadership or failed proposal abandons the pass. Promotion-triggered
cleanup begins only after promotion commits. Only replicated configuration
persists; failure eligibility and pass decisions are local.

The expanded RED capture,
`qraft-cleanup-policy-red-2026-10-21_11-55-17-053.log`, recorded missing runtime
wiring and cleanup. It also exposed a new test-fixture readback race: a public
membership call dispatches to the loop before admitting its sequenced append.
The cleanup fixture now drains both boundaries before reading the pending
index. Production errors were not broadly permitted or suppressed.

The first GREEN capture,
`qraft-cleanup-green-2026-10-21_11-57-54-657.log`, passed 97 cases. After adding
promotion, cancellation, real-WAL reopen, rejection and sync-failure scenarios,
`qraft-cleanup-green-2026-10-21_12-00-37-500.log` passed 104. The deliberately
ambiguous sync failure is attributed through `TestRaftStorageFixture`'s
`RAFT_WAL_TRANSITION_FAILURE` injection to
`RaftNodeCleanupTest#anAmbiguousCleanupSyncFailureFencesTheNodeAndAbandonsThePass`.
All four ERROR headers and four exception headers in those four retained logs
were flagged; no unflagged header or ANSI escape remained. Focused JaCoCo
mismatch warnings reflect changed classes with retained execution data; only
the forthcoming clean build establishes final coverage. Additional boundary
tests were included in five consecutive broader runs, each passing 332 tests
with no failure, error or skip:

- `qraft-cleanup-repeat-2026-10-21_12-02-45-486.log`
- `qraft-cleanup-repeat-2026-10-21_12-05-18-893.log`
- `qraft-cleanup-repeat-2026-10-21_12-07-13-964.log`
- `qraft-cleanup-repeat-2026-10-21_12-09-40-986.log`
- `qraft-cleanup-repeat-2026-10-21_12-12-02-273.log`

The repeat audit covered 115 Maven/application/helper logs: 1,070 ERROR headers
and 990 exception headers, including duplicates, all attributed. No unflagged
header, orphaned stack frame, Maven error or ANSI escape remained in those runs.
The final clean default build passed 1,051 tests, all coverage gates and
packaging, with no failure, error or skip. Its capture is
`qraft-cleanup-final-install-2026-10-21_12-14-06-286.log`. The default audit
covered 25 Maven/application/helper logs: 343 flagged ERROR headers and 292
exception headers, no unflagged header or frame, no ANSI escape, and no JaCoCo
class/data mismatch. The packaged jar includes `RaftMembershipPolicy` and
contains no test fixtures, test Logback configuration or generated logs.
Its SHA-256 is
`953AAA601CCA511BEBFE7A6B1788A173EEE2845C5A0C0266CD3644EC7E033148`.
The complete `docker,e2e,slow` gate on that jar passed 42 tests, with no failure,
error or skip, in `qraft-cleanup-final-runtime-2026-10-21_12-17-32-605.log`.
The image was rebuilt from that jar before the container scenarios. The initial
runtime image was `sha256:f87ecc8b118d834da0670221f2c78621390ae8dad0c0012988a828e2128a29c5`,
created at `2026-10-21T04:18:11Z` (12:18:11 local). A final jar hash check matched
the value above.

The runtime audit read all 113 newly retained files: 107 `.log` files, four
JSON files and two support files, including 98 Docker archives. All 272 ERROR
headers and 99 exception headers were attributed; 2,430 stack frames were
covered by their flagged headers. No unflagged error, exception header or frame,
Maven error, fatal-JVM header or ANSI escape remained. The default and runtime
audits together covered 138 retained files, including duplicate captures, with
615 attributed ERROR headers and 391 exception headers. ERROR counts use the
event's severity; flagged WARN events and their traces were also audited.
Earlier failed RED
and baseline captures remain retained with their causes and fixes above.

Source-header/attribution, prohibited-framework, environment-configuration,
timeout-API and whitespace audits passed. The jar contains no test fixtures,
test Logback configuration or generated logs. Step 5 is closed. Steps 6 to 8
remain open, including the expanded cleanup/replacement container matrix and
the unsupported lost-quorum recovery procedure; the current runtime pass does
not claim those future scenarios were implemented. No commit was made.

Docker preflight initially found a stopped engine with a cached "WSL update
required" startup reason. Installed WSL was already 3.0.4. Restarting the
existing Docker Desktop cleared that state; its engine API then answered as
29.9.1. No WSL installation, data reset or container-test bypass was used.

**Runtime-boundary test preparation, 2026-10-17.** The initial
`RaftNodePromotionTest` additions specified that a stabilization duration just
above one heartbeat requires two whole rounds of health, and that
`Long.MAX_VALUE` milliseconds cannot cause immediate promotion. They use the
existing manual timers and held protocol responses rather than wall-clock
waits or private-counter inspection. Source review identified addition before
ceiling division in promotion and check-quorum, plus addition of election
jitter, as overflow risks. The preparation record claimed no test result;
the first runs and completed fix are recorded below.

**Committed parser verification, 2026-10-17.** At the user's request,
[Jenkins build 27](http://192.168.137.32:8080/job/Qraft/27/) built pushed `main`
at `bef4637`. It passed 1,024 default tests and every coverage gate, 10
end-to-end tests and 32 fresh-image Docker tests: 1,066 results, zero failures,
errors or skips. This includes all 64 `AppConfigRaftMembershipTest` cases.
The retained Maven, application, helper-process and Docker log audit found no
unflagged errors or unattributed exception traces. Complete console, reports,
coverage, archives and local audit records are retained under
`logs/jenkins/Qraft-27/`; [JENKINS.md](../docs/JENKINS.md#build-27-verification-2026-10-17)
records the detailed totals and audit. This closes the parser slice's pending
full verification at that commit. The uncommitted duration-boundary tests above
were not included in build 27; their subsequent verification follows below.
Runtime wiring, automatic cleanup and the overall Step 5 exit gate remain open.

**Timing slice completed and verified, 2026-10-19.** Jenkins replays applied
recorded source patches to `bef4637`, without a commit or push:

- Build 28 ran 72 tests. The largest stabilization duration caused promotion
  at round 2, as the new regression predicted. A separate timeout exposed an
  existing helper race: it could select the leadership no-op's index before
  that queued append completed, acknowledge the no-op, and leave the actual command unacknowledged until
  the bounded wait expired. The helper now drains queued transitions before reading
  the command's next index. The bounded wait was not lengthened.
- Build 29 kept production unchanged, added quorum and jitter boundary cases,
  and ran 74 tests with exactly three assertion failures and zero test errors:
  early promotion, quorum loss at round 2 and a negative election delay.
- `RaftNode` now uses `Math.ceilDiv` for promotion and quorum heartbeat rounds,
  and bounds election jitter by `Long.MAX_VALUE - electionTimeoutMs` before
  adding it. `ManualRaftTimersHelper` exposes the delay requested from the
  scheduler so the jitter test observes the scheduling boundary directly.
- Build 30 passed five consecutive focused runs of 74 tests each, then 1,028
  default tests with all coverage gates, 10 end-to-end and 32 fresh-image
  Docker tests. The full suites total 1,070; the 370 repeat executions bring
  Jenkins's published result count to 1,440. No failures, errors or skips.
- All 892 artifacts were downloaded. The final audit reviewed 143 log files,
  155 Surefire text reports and four JSON configuration documents. All 645
  error headers and 468 exception headers were attributed, including the
  flagged uncaught lock-contender exception. The native Tempo startup event
  had its existing specific marker and matching later WAL recovery. No
  unflagged error, unattributed trace or ANSI escape remained. Failed captures
  were reviewed separately with their causes and fixes, rather than accepted
  as clean runs.

[JENKINS.md](../docs/JENKINS.md#duration-boundary-replay-verification-2026-10-19)
records the builds and source-patch SHA-256. Pipelines and snapshots are in
`logs/jenkins/membership-rounds-2026-10-19/`; build 30's console, reports,
archives, source manifest and audit are under `logs/jenkins/Qraft-30/`.
At completion of the timing slice, the tested patch matched local source, accounting for CRLF.
The changes remain uncommitted; a normal SCM build still uses pushed `main`.

**Server wiring, 2026-10-19.** `QraftServerService` validates all six membership
values before opening transport or storage, passes an immutable
`RaftMembershipPolicy` to the node, and logs the node's effective policy without
the operator token. Promotion reads that policy. Existing promotion builder
setters remain supported, with the last setter winning; cleanup defaults adapt
to the final election and heartbeat timings. Disabled cleanup still validates
its configured bounds. The startup log includes `cleanup.active=false` because
automatic removal has not been implemented.

Seven real-server lifecycle cases cover absent and partial settings, custom
enabled and disabled cleanup, timing-dependent defaults, exact and zero bounds,
and the largest representable values. They make a real HTTP liveness request,
check the effective node policy and token exclusion, stop the service, and
reopen the WAL to prove its lock was released. A manual-round promotion test
proves that the policy controls both lag and stabilization; policy tests cover
validation and overflow-safe conversions.

Jenkins replay build 31 established RED with exactly seven missing-policy-log
assertions among 91 cases, zero test errors and no unrelated application-log
errors. Replay build 32 passed five consecutive 115-case focused runs, 1,059
default tests and every coverage gate, 10 end-to-end and 32 fresh-image Docker
tests. All 916 artifacts are retained. The audit attributed every error and
exception across the retained Maven, application, helper-process and container
logs, and all seven source checksums match the local working tree. The source
patch SHA-256 is
`23a70bff66e1e3a3abc9f1778447e389ea8b034a6237d23ea29897727403da54`.
See [the Jenkins wiring record](../docs/JENKINS.md#membership-wiring-replay-verification-2026-10-19).
Changes remain uncommitted; ordinary Jenkins builds still use pushed `main`.

**Next task: automatic failed-voter cleanup.** Implement the contract below
with manual-round tests for safety guards, failure detection and one committed
removal at a time. The overall Step 5 exit gate remains open.

`QraftServerService` is the class that wires these settings. The single-POM
list's Phase 5 was done on 2026-10-10, and this is what it left:
- `AppConfig` holds its settings as records that follow the document's
  structure. A new group of settings is a record of its own, read in
  `fromJson` and given a getter for each value.
- The shared checks of a document are in `common.config.JsonSettings`.
- There is no process-wide configuration. `QraftServerService` is given its
  `AppConfig`, so the new settings reach it through that object.

#### Configuration contract

The settings under `server.raft.membership` are validated before any runtime
resource opens. Existing files without this object retain the promotion defaults
and receive the timing-aware cleanup defaults.

| Setting | Default | Validation and meaning |
|---|---|---|
| `promotionStabilizationMs` | 10000 | Integer, nonnegative; maps to the existing builder option |
| `promotionMaxTrailingEntries` | 250 | Integer, nonnegative; maps to the existing builder option |
| `cleanup.enabled` | true | Boolean; disabling cleanup does not disable authenticated operator removal |
| `cleanup.failureTimeoutMs` | `max(60000, electionTimeoutMs)` | Positive integer, at least `server.raft.electionTimeoutMs`; continuous absence of contact before a voter is eligible |
| `cleanup.intervalMs` | `max(10000, heartbeatIntervalMs)` | Positive integer, at least `server.raft.heartbeatIntervalMs`; periodic cleanup cadence |
| `cleanup.minVoters` | 3 | Positive integer; automatic cleanup must not leave fewer voters; a smaller existing cluster remains valid and is not expanded automatically |

Unknown fields, wrong JSON types and integers outside their representation
bounds are refused. All representable durations use overflow-safe ceiling division by the
heartbeat interval and are measured in applied heartbeat rounds, as promotion
and check-quorum already are. A delayed loop must not turn elapsed wall-clock
time into fictitious missed rounds. Expose parsed values through `AppConfig`
and wire them through `QraftServerService` to the node; do not read JVM or
environment overrides. Record the effective settings without secrets.

#### Failure detection and replacement sequence

- Only a leader evaluates cleanup. A new leadership starts a fresh failure
  grace period for every configured voter, including one that has never
  answered. Any valid reply in the current leadership resets that voter's
  continuous absence. Stale-generation replies and another server ID do not.
- A promotion that has committed requests an expedited cleanup pass. Otherwise
  the leader evaluates cleanup at the configured interval. Neither trigger
  bypasses the failure grace period, minimum, quorum checks, or change guard.
- Promotion-triggered replacement uses a new server ID with a **distinct name
  and address**: join as a non-voter, stabilize, commit promotion, then evaluate
  failed voters for removal. Cleanup does not infer which old member a join
  intended to replace.
- A wiped server reusing a name or address remains refused under Step 4 until
  the old member's removal commits. The supported operator procedure remains
  authenticated remove first, then join and promote. Periodic cleanup may free
  the name/address first only when all its own safety checks allow removal.
  With the default minimum of three, a three-voter cluster cannot automatically
  remove one before its replacement joins; use the operator procedure or a
  replacement with a distinct name/address. A join never triggers eviction or
  relaxes a cleanup limit.

#### Removal limits and sequencing

- At the start of a pass, freeze the **committed** configuration, its voter
  count `N`, and eligible failed server IDs. Its budget is
  `floor((N - 1) / 2)` voter removals. The denominator is not recomputed after
  each removal in that pass. The budget is per pass, not a lifetime limit;
  later passes remain subject to the minimum and quorum checks.
- Consider eligible IDs in deterministic order. Do not remove the leader or
  automatically remove non-voters in this step.
- Before every proposal, recheck failure eligibility, a recently contacted
  quorum of the current configuration, a recently contacted quorum of the
  proposed configuration, and the configured minimum. A recovered peer is
  skipped. Missing quorum defers cleanup rather than forcing a change.
- Use the same current-term commit and single-change guard as operator removal.
  Await each removal's commit before considering the next; only committed
  removals consume the budget. A pending promotion, join, or operator change
  defers cleanup. An unrelated configuration change, failed proposal, or lost
  leadership abandons the pass; a later pass starts from fresh committed state.
- Never persist a speculative cleanup decision or continue a pass from a prior
  leadership. Report eligibility, deferral reason, and committed removals in
  operational logs; failed detection alone does not alter voting membership.

**Exit gate.**

- Real configuration-parser tests reject invalid, unknown, and overflowing
  settings, and a server lifecycle test proves wiring of non-default
  promotion and cleanup values. An absent object preserves documented defaults,
  including when existing election or heartbeat intervals exceed the usual
  cleanup defaults.
- Manual-timer node tests prove the exact failure and interval boundaries,
  contact reset, new-leader grace, never-answered peers, disabling cleanup,
  promotion-triggered evaluation, and stale or wrong-ID replies.
- Tests prove the frozen budget, minimum, current and proposed quorum checks,
  recovered-peer skip, deterministic ordering, one committed change at a time,
  and cancellation on leadership or unrelated membership change.
- Replacement tests cover both sequences above, including a colliding join
  that cannot evict a member and a three-voter cluster held at the default
  minimum. Real-WAL reopen confirms the committed configuration, not a local
  failure marker, determines voters after restart.
- New behavior is red before green. Changed concurrency tests pass five
  consecutive focused runs;
  `mvn install` and the fresh-image Docker suite pass. The real replacement
  and cleanup scenarios remain mandatory in Step 8 before the list closes.

### Step 6. Protection from disruptive servers

**Status: Done, 2026-10-21; focused, five-repeat and install gates and their log audits passed. Container validation remains in Step 8.**

Leader stickiness was built on 2026-10-02 (see "Review remediation" under
Step 4), with unit tests for a removed server and for the contact-expiry
boundary. `RaftNodeDisruptionTest` now adds five scenarios using the existing
manual-timer cluster and in-memory transport:

- Twenty actual election campaigns from a removed server that retains its old
  voter configuration. Held vote requests are delivered after fresh leader
  contact; both retained voters refuse without changing their term, and a
  subsequent write still commits.
- Heartbeat renewal and refusal one millisecond before the renewed contact
  lease expires, followed by voting at its exact minimum-timeout boundary.
- A higher-term stale-log candidate remains refused after lease expiry; the
  observed higher term still advances, preserving the log freshness rule.
- A partitioned server campaigns to a higher term while missing committed
  entries. After healing, fresh leader contact protects the live voters from
  its campaign. Releasing a genuine higher-term AppendEntries response then
  advances the leader's term; an up-to-date server elects above it, brings the
  returning server up to date and commits another write.
- An isolated leader steps down at the check-quorum boundary without raising
  its term, fails its pending write with an unknown outcome, and the retained
  majority elects a successor. Healing preserves acknowledged writes and
  replaces the isolated leader's uncommitted tail.

The local transport gates retain original requests and futures and release
them through the existing simulator. They control ordering rather than forge
peer answers. No production election behavior or logging allowance has changed.
These are tests of existing behavior. The first focused run, its test-setup
correction, the successful focused rerun, the five consecutive affected runs
and `mvn install` are recorded below. The owner
stopped desktop automation and requested no further desktop control; the owner
ran the documented visible-terminal commands in
[TESTING.md](../docs/TESTING.md#disruptive-server-validation), and all retained
output was inspected before any gate was recorded as passed. Step 8's real
transport disruption and partition-healing scenarios remain open.

**First focused run and correction, 2026-10-21.** The owner ran the documented
seven-class selector. Capture
`logs/qraft-disruption-focused-2026-10-21_17-27-16-405.log` records 64 tests,
one failure, zero errors and zero skips. Four new cases passed. The removed-server
case completed all twenty real campaigns and its functional assertions, then
failed the strict audit: its declared `RAFT_PEER_UNREACHABLE` for `c` occurred
zero times. The retained case output and `recordIfConfiguration`/`peers` show
why: the removal changes the active replication targets before the next send,
so no RPC crosses that partition. The partition preserves the removed server's
old configuration; this scenario does not deliberately cause a transport error.
The incorrect declaration was removed and that ordering is documented in the
test. No error is suppressed and no expectation or failure definition is widened.

The Maven capture and application log
`logs/qraft-maven-tests-2026-10-21_17-27-38.log` are the two retained logs from
this run; no helper or Docker logs were generated. Each holds 31 application
ERROR headers, all explicitly attributed. Each holds 25 attributed exception
headers; the Maven capture additionally holds the test framework's unflagged
`java.lang.AssertionError` for the missing expected occurrence. That audit failure
is the defect corrected above, not accepted noise. Neither capture has ANSI
escapes. The application log has no other unflagged error or exception. The
failed capture remains retained. At that point the corrected focused run and
the completion gates were pending; the focused rerun is recorded next.

**Corrected focused run, 2026-10-21.** Capture
`logs/qraft-disruption-focused-2026-10-21_17-43-47-201.log` records BUILD SUCCESS:
64 tests, zero failures, errors or skips. All five `RaftNodeDisruptionTest`
cases passed, alongside 16 membership, seven check-quorum, three candidate,
two transport-generation, five timer-sequencing and 26 general node tests.
The test source was unchanged after the correction at 17:29; this run finished
at 17:44:57. The application capture is
`logs/qraft-maven-tests-2026-10-21_17-44-41.log`. These are the only two retained
logs from this run; no helper subprocess or Docker archives were generated.

Both captures were read and audited. Each has 31 attributed application ERROR
headers: 25 `RAFT_PEER_UNREACHABLE` and six injected
`RAFT_METADATA_PERSISTENCE_FAILURE` events. Each has 25 attributed exception
headers and 251 associated stack frames, with no unflagged error, exception or
stack frame, no ANSI escape and no JaCoCo execution-data/class mismatch. The
corrected focused gate is verified.

**Five consecutive runs and `mvn install`, 2026-10-21.** The owner ran the
same seven-class selector five consecutive times on unchanged source; no file
under `src/` or `pom.xml` changed after the 17:29 correction. Each run passed
64 tests with zero failures, errors or skips:

- `qraft-disruption-repeat-2026-10-21_18-05-05-813.log`
- `qraft-disruption-repeat-2026-10-21_18-05-26-911.log`
- `qraft-disruption-repeat-2026-10-21_18-05-46-794.log`
- `qraft-disruption-repeat-2026-10-21_18-06-06-741.log`
- `qraft-disruption-repeat-2026-10-21_18-06-31-463.log`

Their application captures are `qraft-maven-tests-2026-10-21_18-05-14.log`,
`_18-05-36`, `_18-05-55`, `_18-06-19` and `_18-06-47`. Each of the ten logs
matches the focused run's audit exactly: 31 attributed ERROR headers (25
`RAFT_PEER_UNREACHABLE`, six injected `RAFT_METADATA_PERSISTENCE_FAILURE`),
31 attributed exception headers (25 `RuntimeException` and six
`InjectedFaultFixture`; the earlier count of 25 covered only the former) and
251 stack frames, with no unflagged header or orphaned frame, no Maven error,
no ANSI escape and no JaCoCo mismatch.

`mvn install`, captured in `qraft-tests-2026-10-21_18-07-13-319.log`, then
passed 1,056 tests (Step 5's 1,051 plus the five disruption scenarios) with
zero failures, errors or skips. All coverage checks were met, and the build
finished with BUILD SUCCESS at 18:09:17. Its application capture
`qraft-maven-tests-2026-10-21_18-07-35.log` and the Maven capture each hold
181 attributed ERROR headers across 24 definitions, 170 attributed exception
headers and 4,249 stack frames. The declared
`GRPC_CONNECTION_ACCEPTED_AS_SERVER_STOPS` WARN appears once, flagged for
`GrpcRaftServerTest#aConnectionPreparedInTheInstantTheServerStopsIsClosedWithOneWarning`.
The default suite's 22 helper-JVM application logs (`_18-08-04` to
`_18-08-38`, 10 of them empty) hold one flagged `WAL_DIRECTORY_ALREADY_LOCKED`
contender error and nothing else at ERROR. The two runtime-process directories
`phase3-runtime-process-client-b1619c04-1b8a-4037-bc96-932aacf433d7` and
`phase3-runtime-process-server-cfccfacb-d02a-4c28-8666-a3ec1941d000` hold
console and configured logs with no error. No Docker archive was written.

The audit read all 38 retained logs from these runs: 673 ERROR headers and 650
exception headers, including duplicates, all attributed, covering 11,008 stack
frames. No unflagged error, exception header or frame, Maven error, ANSI escape
or JaCoCo class/data mismatch remained. Step 6 changed only test code, so its
exit gate needs no fresh-image runtime gate; Step 8 repeats these scenarios over
real transport in containers.

Leader stickiness is the selected protection (thesis section 4.2.3). Do not add
a second election protocol merely to close this validation step.

**Exit gate.** Manual-timer tests repeatedly campaign a removed server and
heal a partition containing a higher-term, stale-log server. They prove a
working leader and its followers keep their term during the contact lease,
the exact expiry boundary permits voting, an isolated leader steps down via
check-quorum, and genuine failover and catch-up still complete.
Run changed concurrency tests five consecutive times and pass `mvn install`.
Step 8 must repeat disruption and partition healing over real transport in
containers before the list closes.

### Step 7. Recovery from lost quorum

**Status: In progress, 2026-10-22. The owner approved the contract (decision 8). The `RaftNode` confirmation tests passed and their logs were audited. Next are the offline command's failing tests. No production code has changed, and no supported recovery procedure exists today.** An older
backup must not be restarted under its former voter ID in an existing cluster.
Offline backup consistency does not prove that later votes and acknowledged
entries can be rolled back safely.

Before changing recovery code, specify the versioned offline recovery input,
its selection/discovery, the authoritative history and retained boundary, how
term/vote and committed/uncommitted entries are handled, survivor identities
and addresses, and atomic publication/one-time consumption. Define interrupted
recovery and repeat-invocation behavior, including recovery from backups rather
than current survivor directories. No recovery setting is accepted in ordinary
server JSON until that contract is implemented.

The operator procedure must stop all surviving processes, quarantine excluded
servers so the old cluster cannot return, preserve complete directories and
backups, and identify the selected survivors before rewriting membership. It
must state which data can be lost and how the operator checks recovered state.
Restarting intact current directories is different from rolling them back to
older backups; whole-cluster backup restore stays unsupported unless this step
explicitly implements and tests it. Decision 8 delivers it.

#### Contract (proposed 2026-10-22)

The contract follows the single-seed model of decision 8. These facts about
the current code shape it:
- Membership lives only in WAL configuration entries and the snapshot envelope.
  The latest configuration in the log is in force, committed or not.
- The commit index is not persisted. A sole voter treats its whole recovered
  log as committed (`RaftNode` recovery).
- AppendEntries and InstallSnapshot that name another server ID are refused
  before term, log or leader change. Replies count only from the server ID the
  configuration records.
- A leader ignores every vote request. A follower ignores vote requests while
  it has live leader contact.
- Opening RaftLog or the snapshot store can repair a torn tail and set aside
  interrupted files. That is a change to the directory.
- Ordinary startup gives a populated directory without `server-id` a new ID.
- There is no offline command today. `qraft operator raft` only calls a
  running server over HTTP.

**Against Consul.** The procedure follows Consul's single-survivor recovery
(section 3), with these differences:

| Concern | Consul | Qraft |
|---|---|---|
| Recovery input | `raft/peers.json` placed in the survivor's data directory | `recovery.json` passed to an offline command; nothing is placed in a data directory |
| Surviving servers recovered | One or more, all listed in the file | Exactly one seed; the others rejoin empty |
| Survivor's directory | Rewritten in place at startup | Copied; the source is never changed |
| Seed's identity | Keeps its node ID | Gets a new server ID |
| One-time use | File deleted after a successful recovery | Claim file beside the source; ordinary startup reads neither |
| Uncommitted entries | Implicitly committed | Committed by the sole voter at first start |
| Lost servers | Rebuilt empty and rejoin | The same, under new server IDs |
| Keeping the old cluster out | The lost servers are destroyed and redeployed | Server-ID checks and leader stickiness, plus mandatory quarantine |
| Restore from backup | Fresh cluster, then `consul snapshot restore` over HTTP | The backup becomes the seed, through the same offline command |

Two reasons drive the departures:
- The runbook forbids editing files inside a data directory.
- An older copy must not return under its former voter ID.

The new server ID is also what fences off the old cluster. Qraft has no API to
import a snapshot into a running cluster, so Consul's backup path has no direct
counterpart.

**Commands.** Two offline subcommands of `qraft operator raft` use its exit
codes: 0 done, 1 refused, 2 misuse.
- Neither contacts a server.
- Ordinary `qraft server` startup never reads their inputs or outputs.
- `inspect --dir <path>` reports on a stopped directory without changing it:
  - server ID, term and vote;
  - last log index and term;
  - snapshot index and term;
  - compaction boundary;
  - the configuration in force and where it came from;
  - whether ordinary startup would refuse the directory, and why.

  It works on a byte copy in a temporary directory and refuses a directory
  whose lock is held.
- `recover --input <recovery.json>` performs the recovery below.

**Recovery input.** A versioned JSON file, passed explicitly and never
discovered:

```json
{
  "version": 1,
  "source": { "path": "/backup/node-a-2026-10-22", "serverId": "<the source's server-id>" },
  "serverConfig": "/etc/qraft/server.json"
}
```

- It is read with the server JSON rules. `version` must be 1. Unknown or
  duplicate keys, placeholders and missing values are refused.
- `source.serverId` must equal the source's `server-id`, so the wrong
  directory cannot be chosen by mistake.
- The output directory, name and Raft address come from `serverConfig`: its
  `server.raft.storage.path`, `server.id` and `server.raft.nodes[server.id]`.
  The recovered server then starts with that same file, so they cannot
  disagree. Relative paths resolve as the server resolves them.
- Server JSON gains no recovery setting.

**Selecting the seed.** The operator stops every surviving process, runs
`inspect` on each surviving directory and usable backup, and picks the
most up-to-date log by Raft's rule: highest last log term, then highest last
log index.

`recover` refuses a source when:
- its lock is held;
- its `server-id` is missing or unreadable. Unlike ordinary startup, recovery
  cannot establish whose history the directory holds;
- ordinary startup would refuse it: a corrupt or unsupported WAL or metadata,
  a damaged snapshot envelope, compaction past the published snapshot, no
  configuration, or an unpublished rewrite;
- its server ID is not a member of its own configuration in force, as with a
  removed server;
- the source and the output are the same directory, or one contains the
  other;
- the output already exists (see repeat invocation).

**Authoritative history and what is lost.** The seed's history becomes the
recovered cluster's history.
- Every entry in the seed's log is kept, including entries it never learned
  were committed. The seed cannot tell those apart, so as sole voter it
  commits all of them at its first start. A write whose client saw an unknown
  outcome may therefore appear.
- Entries that only the excluded servers held are lost, even if the old
  cluster committed and acknowledged them.
- With a backup as seed, everything after the backup is lost.
- The catalog is the state machine at the seed's last entry. The container
  scenarios record what clients observe afterwards.

**The rewritten directory.** The command never changes the source. It works in
a staging directory beside the output, on the same filesystem, in this order:
1. It holds the source's `raft.lock` for the whole copy, so no server can start
   on the source meanwhile.
2. It copies every file byte for byte, forces the copies and compares checksums
   with the source. The exception is RaftLog's `raft.lock`, which holds no data
   and is recreated empty. On Windows, the lock held in step 1 blocks reading
   that file through another handle.
3. It opens the copy with the ordinary storage and recovery checks, and refuses
   on any failure.
4. It appends one configuration entry at the next index, in term
   T = max(current term, last log term) + 1. The entry lists a single voter:
   a newly generated server ID, with the name and address from `serverConfig`.
   The ID must differ from every server ID in the retained configurations.
5. It syncs the WAL, then sets the persistent metadata to term T with no vote.
6. It replaces `server-id` with the new ID: temp file, force, atomic move,
   directory force.
7. It closes the storage. It then publishes by renaming the staging directory
   to the output and forcing the parent directory.

The index-1 bootstrap entry and every earlier entry stay as they were. No WAL,
snapshot, metadata or wire format changes.

**One-time use.** Before step 1, the command creates a claim file beside the
source, `<source>.qraft-recovery.json`. It is created new, never overwritten,
and forced to disk.
- The claim records the source server ID and the output path. Once they exist,
  it also records the new server ID and the recovery index and term.
- A later `recover` from the same source with a different output is refused,
  so one source cannot seed two clusters.
- The claim never enters a data directory, and startup never reads it.
- The source's parent directory must be persistent and writable, because the
  claim lives there. In containers, a source volume mounted at a mount root
  such as `/app/data` would put the claim in the throwaway container layer,
  and it would vanish when the container exits. So the source volume is
  mounted inside a persistent recovery volume, at `/recovery/source`, and the
  claim, staging directory and output (`/recovery/seed`) all live on that
  volume. Decided 2026-10-23.
- A source on read-only media is first copied to writable storage as an
  offline backup copy, under the runbook's backup rules.

**Interruption and repeat invocation.**
- **Interrupted before publication:** the output does not exist. The staging
  directory carries a marker and is never a valid data directory. The next
  `recover` with the same input removes its own marked staging directory and
  starts again from the unchanged source, with a new server ID, since the old
  one was never published.
- **Interrupted after the rename, before the claim records publication:** the
  next run finds the output and checks that its `server-id` matches the claim.
  It then records publication and exits 0 without other changes.
- **After a complete recovery:** `recover` with the same input exits 1 and
  changes nothing.
- No partial staging directory is ever accepted as a recovered directory.

**Excluded servers and the other survivors.**
- Every directory except the seed's output is excluded: the seed's source, the
  other survivors and the lost servers. They are kept as evidence and never
  started again under their server IDs.
- To return, a server starts on an empty directory with its usual server JSON.
  It gets a new server ID, joins through the existing path, and is promoted
  under Step 5's policy. The recovered configuration names only the seed, so no
  name or address collision blocks it.
- Quarantine is mandatory. Server IDs keep the two clusters apart:
  - the seed and the rejoined servers refuse AppendEntries and InstallSnapshot
    meant for the old IDs;
  - the old configuration counts no reply from the new IDs;
  - the recovered leader ignores old campaigns.

  But excluded directories that hold an old majority could still start and
  serve their own clients as the old cluster. The runbook therefore requires
  every excluded server to stay stopped until it is wiped. A wiped server must
  not list a still-running excluded server among its contacts.
- One seed is enough. The recovered cluster starts below `cleanup.minVoters`
  and grows by joins. Cleanup never takes it below that minimum.

**Checking the recovered state.**
- Before start, `inspect` on the output shows the new server ID and the
  recovery entry's index and term.
- After start, `/raft/status` shows the seed as sole voter and leader, with a
  commit index at or above the recovery index.
- `list-peers` lists only the seed, then each rejoined server as a non-voter
  and later a voter.
- The operator checks known registrations and makes a test write.
- The runbook publishes its commands and observed results only after the exit
  gate.

**Red tests before code.** `RaftNodeLostQuorumRecoveryTest` (2026-10-22)
builds the seed's directory as the command will, in its `seedFrom` helper,
with these steps:
1. a byte copy of a stopped survivor's real WAL directory;
2. the recovery entry at the next index, in term T;
3. the persistent term set to T with no vote.

Its five cases cover the three points below, and one more: a running leader
refuses the recovery configuration as a change. They test existing `RaftNode`
behaviour, so they may pass at once. A failure shows that the contract needs a
production change before the command is built. The command's own failing
tests follow, once these results are recorded.

**First confirmation run and correction, 2026-10-22.** Capture
`logs/qraft-recovery-confirm-2026-10-22_11-29-53-657.log` records five tests
and four failures. Only `aRunningLeaderRefusesTheRecoveryConfigurationAsAChange`
passed.
- All four failures came from the test's own setup, at the check "no quorum
  remains to commit the tail" in `loseTheMajority`. None reached the behaviour
  under test.
- The cause: `loseTheMajority` read a's last log index after
  `cluster.settle()`. The leader adds the entry to its log only once the WAL
  append completes, and `settle()` drains the network and the state loop, not
  storage writes. So the read saw index 3, equal to the commit index. The log
  then shows "Command submitted at index 4", followed at once by the teardown
  stop.
- The correction: the helper now waits until the tail is in a's log, then
  settles so the failed sends to b and c are logged. No production code,
  expectation or failure definition changed.
- The audit read both retained logs. The application log
  `logs/qraft-maven-tests-2026-10-22_11-30-32.log` holds two attributed
  `RAFT_PEER_UNREACHABLE` headers and nothing unflagged. The Maven capture
  additionally holds the four `AssertionFailedError`s and Maven's `[ERROR]`
  summary of them. Those are the defect corrected here, not accepted noise.
  Neither log has ANSI escapes.

**Second confirmation run and correction, 2026-10-22.** Capture
`logs/qraft-recovery-confirm-2026-10-22_11-37-53-807.log` records five tests,
zero failures and one error. Four tests passed:
- the leader refusal;
- the seed's first start, which commits its whole log and elects itself;
- both joins, by replication and by installed snapshot.

The error came from the old-majority test's setup:
`IllegalStateException: no election timer is armed`, raised on old b's second
campaign.
- The cause is the same storage race as the first run. A durable node persists
  its campaign term on the WAL thread, then sends its votes and re-arms its
  election timer. `settle()` returned before that write finished, so the next
  fire found no timer. `RaftNodeDisruptionTest` fires campaigns the same way,
  but on volatile storage.
- The correction: the loop now waits until b's term has advanced and its next
  election timeout is armed. `RaftNode` does both in one state-loop
  continuation after the durable write.
- The audit read both retained logs. The application log
  `logs/qraft-maven-tests-2026-10-22_11-38-44.log` holds ten attributed
  `RAFT_PEER_UNREACHABLE` headers and nothing unflagged. Two lost peers in each
  of four tests account for eight. In the erroring test, b's campaign toward
  the stopped c accounts for one. The last comes from that test's teardown:
  b's own transport stopped before the seed's vote reply arrived. The Maven
  capture additionally holds the `IllegalStateException` and Maven's summary
  of it, which is the defect corrected here.

**Green confirmation runs, 2026-10-22.** The owner ran the corrected class
twice. Each run passed five tests, with zero failures, errors or skips:
- `logs/qraft-recovery-confirm-2026-10-22_12-47-04-903.log`, with application
  log `qraft-maven-tests-2026-10-22_12-48-31.log`;
- `logs/qraft-recovery-confirm-2026-10-22_13-14-06-940.log`, with application
  log `qraft-maven-tests-2026-10-22_13-14-29.log`.

All three behaviours below hold on unchanged production code. The contract
needs no `RaftNode` change.

The audit read all four logs. Each has 12 attributed ERROR headers, all
`RAFT_PEER_UNREACHABLE`, with 60 stack frames, and none unflagged:
- 8 come from the two lost peers in each of the four tests that lose the
  majority;
- 3 are old b's deliberate campaigns while c is down;
- 1 is old b's write toward the stopped c.

The 13 WARN events, without stack traces, are the server-ID fence working.
Both messages come from `fromConfiguredServer`, and only old b logs them,
since its configuration records "a" under the old server ID:
- 4 × "Ignoring a vote response from a with server ID recovered-a";
- 9 × "Ignoring an AppendEntries response from a with server ID recovered-a".

The one Maven `[WARNING]` is the protobuf plugin's existing `binaryMavenPlugins`
deprecation, already present in the 2026-10-21 install capture. No ANSI escape,
JaCoCo mismatch or Maven error remains.

**Offline command API and red tests, 2026-10-22.** Recovery has to decode and
validate the WAL with the `state` codec and read the server JSON through
`AppConfig`, so `PackageDependencyTest`'s layer rules place it in the server
layer. The new package is `dev.mars.qraft.server.recovery`; `runtime` will only
parse the CLI arguments, as `RaftOperatorCommand` does.
- `RecoveryInput.read(Path)` parses `recovery.json` and the server JSON it
  names into a `RecoveryPlan`: source, expected server ID, output, name and
  address. Invalid input throws `IllegalArgumentException`, which the CLI maps
  to exit 2.
- `OfflineRecovery.inspect(Path)` returns a `DirectoryReport`.
  `OfflineRecovery.recover(RecoveryPlan)` returns a `RecoveryResult` with the
  new server ID, the recovery index and term, and an outcome:
  `RECOVERED`, or `PUBLICATION_RECORDED` for a rerun after an interrupted
  publication.
- A refusal throws the checked `RecoveryRefusedException`, which the CLI maps
  to exit 1.

`inspect`, `recover` and `read` are stubs that throw
`UnsupportedOperationException`, so the tests compile and fail for the right
reason. The tests are:
- `OfflineRecoveryTest`, 15 cases on real storage in this JVM, using the new
  `SurvivorDirectoryFixture`:
  - the published seed: history kept, one recovery entry in term 2 with a
    single voter under a new canonical UUID, metadata at term 2 with no vote,
    the source byte-identical, a claim beside it, and no staging directory
    left;
  - a real node on the output commits its whole log and elects itself;
  - `inspect` on a healthy directory, and on one that ordinary startup would
    refuse;
  - the refusals: mismatched, missing and unreadable server IDs, a locked
    source, an existing output, an overlapping output, a removed source, a
    source without configuration, a corrupt source, a second output from the
    same source, and a repeated completed recovery.
- `RecoveryInputTest`, 8 cases: the resolved plan; an unsupported version;
  unknown and duplicate keys; placeholders; each missing value; an unreadable
  or invalid server JSON; and a server JSON that does not list its own address.

**Red run, 2026-10-22.** Capture
`logs/qraft-recovery-red-2026-10-22_13-32-35-362.log` records 32 tests, 15
failures and 8 errors. `PackageDependencyTest` passed all 9 of its tests,
which classifies the new package in the server layer.
- 22 of the 23 recovery tests failed at the stubs: 7 with the
  `UnsupportedOperationException` itself, and 15 with "Unexpected exception
  type thrown ... but was: UnsupportedOperationException".
- The three intentional-error declarations reported "occurred 0 time(s)", as
  expected: the stubs return before any recovery check runs.
- `aLockedSourceIsRefused` failed earlier, in the test. The fixture's digest
  read `raft.lock` while the test held the lock, and on Windows a byte-range
  lock blocks reading the file through any other handle. The digest now
  records only that file's size, since it holds no data.
- The same Windows behaviour constrains the implementation. `recover` holds
  the source's lock during the copy, so it cannot read `raft.lock` either. It
  recreates the file empty in the copy instead of copying its bytes.
- The application log `logs/qraft-maven-tests-2026-10-22_13-33-17.log` holds
  no ERROR or WARN event. The Maven capture's unflagged exceptions are these 23
  red failures and nothing else.

**Implementation, 2026-10-22.** `RecoveryInput`, `inspect` and `recover` are
implemented; the focused green run is pending in the owner's terminal.
- `ServerIdentity` gains three public methods: `existing`, which reads an ID
  without creating one; `replace`, an atomic write used only on the recovery's
  staging directory; and `forceDirectory`, which was already there and is now
  public.
- `SourceLock` opens the source's `raft.lock` without creating or writing it,
  and holds its exclusive lock for the whole run. A lock held by another process
  and one held by storage open in this JVM are both refused as "is locked".
- `DirectoryCopy` copies, forces and verifies every file by SHA-256. It
  recreates `raft.lock` empty and refuses any entry that is neither a file nor
  a directory.
- `RecoveryExamination` reads the copy's storage for the report, then starts a
  real `RaftNode` on the copy and stops it before any election. The node uses
  the directory's own server ID, an offline transport that refuses every send,
  and a one-hour election timeout, so its checks are exactly those of ordinary
  startup. A directory with no Raft state is refused without starting a node,
  because a node would bootstrap it.
- `RecoveryClaim` writes the claim with create-new and replaces it atomically
  at each step: claimed, then prepared with the new ID, index and term, then
  published.
- The staging directory is `.<output name>.qraft-recovering` beside the output.
  Its name is the marker; a rerun deletes it, and a refusal removes it.
- The read-only checks run first, so a plan they refuse makes no claim: overlap,
  source server ID, the claim's state, and an existing output. (The
  claim-release correction below also covers a source the examination refuses.)
  Then the source
  lock is taken, the claim is created, and the copy, examination, recovery
  entry, new ID and atomic rename follow.
- An unrecorded publication from an interrupted run is recognised by the
  output's server ID matching the claim, and returns `PUBLICATION_RECORDED`.
  The separate-JVM crash tests will exercise that path.

**First green run and correction, 2026-10-22.** Capture
`logs/qraft-recovery-green-2026-10-22_14-15-15-395.log` records 38 tests,
zero failures and four errors. `RecoveryInputTest` (8), `ServerIdentityTest`
(6) and `PackageDependencyTest` (9) passed, as did 11 of the 15
`OfflineRecoveryTest` cases: every refusal and both `inspect` cases.
- The four errors are the four cases in which recovery completes. Each was
  refused with "Writing the recovery entry failed: Log tail is unknown: call
  replayLog() before writing".
- The cause: RaftLog refuses an append to an existing WAL until `replayLog()`
  has run, and `appendRecoveryEntry` appended straight after opening the
  staging WAL.
- The correction: it now replays first and checks that the tail is the index
  the examination reported. If it isn't, recovery is refused with nothing
  written.
- The audit read both logs.
  - The application log `logs/qraft-maven-tests-2026-10-22_14-16-15.log` holds
    six attributed ERROR headers:
    - `RAFT_STATE_WITHOUT_CONFIGURATION` twice, from the two ordinary-startup
      refusals;
    - `WAL_AMBIGUOUS_CORRUPTION` twice and `RAFT_RECOVERY_AMBIGUOUS_CORRUPTION`
      twice, because the corrupt-source copy is replayed once for the report
      and once by the node.
  - It also holds four unflagged RaftLog `Refused append: LOG_STATE_UNKNOWN`
    errors. They are the defect corrected here.
  - Two WARN events without stack traces, "Record at position 0 cannot be
    decoded", are RaftLog detecting the deliberately flipped byte in each
    replay.
  - The Maven capture's only unflagged exceptions are the four refusals above.

**Claim release on refusal, 2026-10-22.** A review of commit `885f92e` found
that the claim was created before the copy was examined, so a source that
ordinary startup refuses, a removed source, or a corrupt one was left claimed
for its output. A retry with another output was then refused as "already
claimed", although nothing had been published and the claim file must not be
deleted by hand. The contract's "Before step 1" claim stands; the claim is now
released again when the run that made it refuses before the claim records a new
server ID (after that, the output may exist, and the claim stays).
- Tests first. `aRemovedSourceIsRefused`,
  `aSourceThatOrdinaryStartupWouldRefuseIsRefused` and
  `aCorruptSourceIsRefusedAndKeptAsEvidence` now assert that no claim remains;
  `aSourceRefusedAfterCopyingMayStillSeedAnotherOutput` retries with another
  output; `aRefusedRunReleasesOnlyTheClaimItMade` pins that a claim from an
  earlier run is left as it was. Red run
  `logs/qraft-claim-release-red-2026-10-22_17-28-38-430.log`: 17 tests, four
  failures, the guard test passing.
- Then `OfflineRecovery.publish` releases the claim on a refusal when
  `madeHere` and it is not yet recorded, through the new
  `RecoveryClaim.release()`.
- Green: `logs/qraft-claim-release-green-2026-10-22_17-30-00-547.log` records 40
  focused tests (`OfflineRecoveryTest` 17, `RecoveryInputTest` 8,
  `ServerIdentityTest` 6, `PackageDependencyTest` 9), zero failures. The
  application log `logs/qraft-maven-tests-2026-10-22_17-30-05.log` and the
  console capture hold six ERROR events, all flagged intentional and naming
  their test, and two stackless RaftLog WARN events for the deliberately
  flipped byte. The earlier capture `qraft-claim-release-green-2026-10-22_17-29-31-983.log` is
  incomplete (its output filter closed the pipeline) and counts for nothing. This was a small local
  check, not the verification run: `mvn
  install` and Jenkins follow.

**Focused green run in this workspace, 2026-10-23.** The 2026-10-22 captures
cited in "Claim release on refusal" above are not retained in this
workspace's `logs/`, so the owner reran the focused classes on `960290d`.
- Capture `logs/qraft-recovery-green-2026-10-23_10-56-48-538.log` records 40
  tests with zero failures, errors or skips: `OfflineRecoveryTest` 17,
  `RecoveryInputTest` 8, `ServerIdentityTest` 6 and `PackageDependencyTest` 9.
  This confirms both the `replayLog` correction and the claim release.
- The application log `logs/qraft-maven-tests-2026-10-23_10-57-28.log` and
  the Maven capture each hold six attributed ERROR headers and nothing
  unflagged:
  - `RAFT_STATE_WITHOUT_CONFIGURATION` twice, from
    `aSourceThatOrdinaryStartupWouldRefuseIsRefused` and
    `inspectReportsWhyOrdinaryStartupWouldRefuseADirectory`;
  - `WAL_AMBIGUOUS_CORRUPTION` twice and `RAFT_RECOVERY_AMBIGUOUS_CORRUPTION`
    twice, from `aCorruptSourceIsRefusedAndKeptAsEvidence`.
- The two stackless WARN events, "Record at position 0 cannot be decoded", are
  RaftLog detecting the flipped byte in each replay.
- The Maven capture's 17 JaCoCo "Execution data … does not match" warnings all
  name `RaftNode` inner classes. The cause is that `target/jacoco.exec` is
  appended to across runs and still holds data recorded before the merge
  changed `RaftNode`. As with Step 5's focused runs, only the clean build
  establishes coverage.
- The other two `[WARNING]` lines are JaCoCo's bundle summary of those
  mismatches and the existing protobuf `binaryMavenPlugins` deprecation.
- No ANSI escape or Maven error remains.

**Separate-JVM tests, 2026-10-23.** These follow `FileSnapshotStore`'s
pattern.
- `RecoveryCheckpoint` names each durable step of `recover`, in order:
  `CLAIM_CREATED`, `SOURCE_COPIED`, `EXAMINED`, `RECOVERY_ENTRY_SYNCED`,
  `METADATA_UPDATED`, `SERVER_ID_REPLACED`, `CLAIM_PREPARED`, `PUBLISHED` and
  `CLAIM_PUBLISHED`.
- A package-private `recover(plan, checkpoints)` overload tells a listener of
  each step. The public `recover(plan)` passes one that does nothing, so
  behaviour is unchanged.
- `RecoveryCrashWriterFixture` runs `recover` in a separate JVM and halts it at
  the chosen checkpoint.
- `OfflineRecoveryProcessTest`, 11 cases, audits each halted JVM's whole output
  with `SubprocessOutputAuditHelper`:
  - a halt at each of the seven steps before publication leaves no output, an
    unchanged source and a surviving claim, and the next run publishes a valid
    seed;
  - a halt at `PUBLISHED` is completed by the next run as
    `PUBLICATION_RECORDED`, with the claimed server ID, index 4 and term 2, and
    the output byte-identical;
  - a halt at `CLAIM_PUBLISHED` is refused as "already recovered", with nothing
    changed;
  - a source locked by `DirectoryLockProcessFixture` in another JVM is refused
    by both `recover` and `inspect`, with no claim made;
  - after a halt at `SERVER_ID_REPLACED`, `ServerIdentity` and a `RaftNode`
    started on the source as ordinary startup does keep its own ID, old
    configuration and log, and leave the claim and the staging directory
    byte-identical.

The behaviour these tests cover is already implemented, so they may pass at
once. A failure is a defect in `recover`.

**Separate-JVM run, 2026-10-23.** Capture
`logs/qraft-recovery-process-2026-10-23_11-09-55-517.log` records 28 tests
with zero failures, errors or skips: `OfflineRecoveryProcessTest` 11 and
`OfflineRecoveryTest` 17. `recover` needed no change.
- The audit read all 13 logs from the run.
  - The main application log `logs/qraft-maven-tests-2026-10-23_11-10-36.log`
    and the Maven capture each hold the same six attributed ERROR headers from
    `OfflineRecoveryTest` as the previous run, and the two stackless RaftLog
    WARN events for the flipped byte. `OfflineRecoveryProcessTest` logged no
    error.
  - The 11 helper-JVM logs, `_11-10-37` to `_11-10-49` (ten crash writers and
    one lock owner), hold no ERROR or WARN event.
  - Two of those logs are empty: a JVM halted at `CLAIM_CREATED` or
    `SOURCE_COPIED` stops before any storage or node code has logged. The
    six-line log is the lock owner, which only opens and closes its WAL.
  - The tests themselves audited each halted JVM's console output, with
    `SubprocessOutputAuditHelper`.
- The Maven capture's 17 JaCoCo mismatch warnings have the same cause as
  before: `jacoco.exec` still holds data from before the merge changed
  `RaftNode`. No ANSI escape or Maven error remains.

**CLI red tests, 2026-10-23.** The offline commands join `list-peers` and
`remove-peer` in `RaftOperatorCommand`, with the same exit codes: 0 done,
1 refused, 2 misuse.
- `qraft operator raft inspect --dir <path>` prints `name  value` lines, then
  a table of the configuration's servers. Its exit code is 0 even when the
  report says ordinary startup would refuse the directory.
- `qraft operator raft recover --input <recovery.json>` prints the outcome,
  the output path, the new server ID and the recovery index and term.
- An invalid recovery input is misuse.

`RaftOperatorRecoveryCommandTest` has seven cases:
- the `inspect` report and its table;
- `inspect` explaining a refusal;
- a locked directory refused with 1;
- a published seed and its printed identity;
- a refused recovery with 1, nothing published and the source unchanged;
- an invalid or missing input with 2;
- command-line misuse printing the usage.

`SurvivorDirectoryFixture` is now public, for use from the `runtime` tests.

**CLI red run and implementation, 2026-10-23.** Capture
`logs/qraft-recovery-cli-red-2026-10-23_11-58-51-314.log` records 14 tests
and seven failures.
- `RaftOperatorCommandTest` passed 7 of 7.
- Every `RaftOperatorRecoveryCommandTest` case failed at the old command's
  response, "Unknown command: inspect" or "Unknown command: recover", or with
  exit code 2 where 1 was expected. The one declaration reported "occurred 0
  time(s)", as expected.
- The application log `logs/qraft-maven-tests-2026-10-23_11-59-24.log` holds
  no ERROR or WARN event. The Maven capture's only unflagged exceptions are the
  seven `AssertionFailedError`s.

The implementation:
- `RaftOperatorCommand` accepts `inspect --dir` and `recover --input` and runs
  them before any HTTP client is created.
- The per-subcommand option checks move to `requireOptions`.
- `list-peers` and the `inspect` table share `printTable`.
- An invalid recovery input prints its message and exits 2; a refusal prints
  "Refused: …" and exits 1.

From the jar, the recovery's own logs use the production Logback defaults:
`./logs` under the `server` mode name, plus the console. A separate operator
log mode is noted for the runbook stage.

**CLI green run, 2026-10-23.** Capture
`logs/qraft-recovery-cli-green-2026-10-23_13-10-45-788.log`, on `13bfd90`,
records 32 tests with zero failures, errors or skips:
`RaftOperatorRecoveryCommandTest` 7, `RaftOperatorCommandTest` 7,
`QraftRuntimeApplicationTest` 9 and `PackageDependencyTest` 9.
- The application log `logs/qraft-maven-tests-2026-10-23_13-11-05.log` and
  the Maven capture each hold one attributed ERROR header and nothing
  unflagged. It is `RAFT_STATE_WITHOUT_CONFIGURATION` from
  `inspectSaysWhyOrdinaryStartupWouldRefuseADirectory`.
- The Maven warnings are the stale JaCoCo data recorded above and the protobuf
  `binaryMavenPlugins` deprecation. No ANSI escape or Maven error remains.

**Container scenarios written, 2026-10-23.** `DockerLostQuorumRecoveryTest`
(`@Tag("docker")`) runs the offline commands from the `qraft-runtime:test`
image, whose entrypoint passes `operator ...` to the jar.
- Layout: the source volume is mounted inside a new recovery volume, at
  `/recovery/source`, so the claim, the staging directory and the seed
  (`/recovery/seed`) persist (contract, one-time use).
- The seed runs as a detached container with the network alias `server1` and
  server1's acceptance configuration, with its storage path changed to
  `/recovery/seed`. The compose `server1`, the recovery's source, stays
  stopped.
- From current directories:
  1. Commit a write and wait for first snapshots, then stop server2 and
     server3, losing the majority, and stop server1.
  2. `inspect` the stopped directory, which ordinary startup accepts, then
     `recover` it into a seed with a new server ID.
  3. The seed leads as the sole voter and keeps the committed write.
- From a backup:
  1. Commit a write, stop server1 and copy its complete directory to a backup
     volume, then restart it and commit a second write.
  2. Lose the whole cluster and recover from the backup.
  3. The seed holds the first write and not the second.
- In both, quarantine is then broken on purpose. The old server2 and server3
  restart on their own volumes, elect a leader and commit their own write. The
  test waits until an old server's log shows the seed's AppendEntries answers
  ignored under the new server ID, then shows the seed's state, term,
  configuration and data unchanged.
- Then server2 and server3 are wiped and restarted. They join the seed through
  the ordinary path, are promoted, and leave three voters under new server IDs
  with no old ID among them. A new write reaches all three, and the old
  cluster's write is gone.
- The declared errors are `RAFT_PEER_UNREACHABLE` and
  `RAFT_PEER_NAME_UNRESOLVED`, for stopped servers. Anything else the audit
  finds is investigated.
- `SharedDockerClusterFixture` gains `startDetachedServer`, with a published
  loopback HTTP port and its logs captured on close, plus `volumeName`,
  `createVolume`, `removeVolume`, `runAlpine` and `runQraftCommand`, which
  captures its output for the audit.

The first run follows the clean `mvn install`, so the image uses a current jar.

**Container scenarios reviewed and verified, 2026-10-24.** Review of `bbbc385`
found three defects in the tests, fixed in `55fbb1c` with no production change:
- the method timeout of 10 minutes was below the worst-case sum of the
  bounded waits (about 12 minutes), and its comment claimed otherwise; it is
  now 25 minutes and the comment gives the figure;
- `anyNodeHas` counted an endpoint that could not answer as one that had the
  write, so a transient HTTP failure could fail a negative assertion. It is
  replaced by `assertNoNodeHas`, which waits up to 30 seconds for every
  endpoint to answer that the write is absent;
- the `finally` blocks stopped at the first failing cleanup step, and
  `removeVolume` ignored a failed removal. A `cleanUp` helper now runs every
  step and reports the first failure with the others suppressed, and
  `removeVolume` fails if Docker cannot remove the volume.

Verification, on JDK 27:
- The first focused run, before `mvn install`, was refused by the fixture
  because the runtime jar was older than `RecoveryCheckpoint.java`, which is
  its intended guard; no scenario started.
- `mvn clean install`: 1,138 tests, no failures, errors or skips, every
  JaCoCo gate met, and no unflagged error in the Maven capture.
- `DockerLostQuorumRecoveryTest`, both scenarios, passed once (115 s) and then
  in five consecutive runs (80 to 115 s each), 2 tests per run. The captures
  and the Docker archive hold 28 ERROR headers, all
  `RAFT_PEER_UNREACHABLE` flagged to the test, and 28 stack traces that match
  them one for one. The only warnings are the expected "Ignoring an
  AppendEntries/vote response from server1 with server ID ..." lines of the
  quarantine break and the existing unauthenticated admin-interface notice.
  The risks noted in review, host ports changing on restart and unflagged
  errors from the seed refusing the old cluster, did not occur.
- Jenkins build 33 on `55fbb1c` (`MUTATION_TESTING=off`) succeeded in 718 s:
  1,138 default, 10 end-to-end and 34 Docker tests (32 before, plus these
  two), no failures, errors or skips. The retained archive under
  `logs/jenkins/Qraft-33/` holds 664 ERROR headers, all flagged, and no stack
  trace under an unflagged header at any level.

The offline command and both container scenarios are complete and verified:
`RecoveryInput`, `inspect`, `recover`, the crash boundaries, separate-JVM
locking, the CLI, and recovery from current directories and from backups.
The runbook and the design update were written on 2026-10-24 from the
observed results above: "Recover a cluster that lost its quorum" in
`docs/RAFT_STORAGE_OPERATIONS.md` (procedure, rules, observed output, limits,
and the three "no supported lost-quorum recovery" statements replaced),
section 14.5 of the design, and the feature validation record. Step 7's exit
gate is met, subject to your review of those documents. Step 8, the container
scenarios, is next.

Then the CLI. Write failing tests first that show:
- followers and joining servers accept the recovery entry by replication and by
  installed snapshot, although a running leader would refuse that change;
- the seed's first start elects it and commits its whole log;
- old-cluster isolation holds. A returned old majority's AppendEntries,
  InstallSnapshot and campaigns neither change the recovered cluster nor gain a
  counted reply.

**Exit gate.**

- Real-storage tests in separate JVMs refuse:
  - a malformed input or one with duplicate keys;
  - a mismatched source server ID;
  - a missing or unreadable `server-id`;
  - incompatible or corrupt data;
  - a locked source;
  - a removed source;
  - conflicting recovery inputs: the same source with another output, or
    overlapping source and output;
  - an existing output.

  They also prove that ordinary startup never acts on a claim, a staging
  directory or a recovery input.
- Crash tests stop the command at each numbered boundary above, and between
  publication and the claim update. A rerun either completes or refuses
  without change. No partial staging directory is accepted, and the source's
  bytes are unchanged throughout.
- A container scenario from current directories:
  1. It loses the majority and quarantines the excluded servers.
  2. It inspects the survivors and recovers the most up-to-date one as seed.
  3. It elects one leader.
  4. It verifies the retained catalog state and later writes.
  5. It rejoins wiped servers under new IDs and promotes them to the original
     size.
  6. It restarts an excluded old-majority directory and shows that it neither
     changes the recovered cluster nor counts the new servers' replies.
- A container scenario from backups:
  1. It recovers from a backup that predates later participation.
  2. It shows that entries after the backup are absent and those before it are
     present.
  3. It shows the same isolation of the old voters.
- Focused concurrency tests pass five
  consecutive runs, and the full default, end-to-end, and fresh-image Docker
  suites pass. Publish executable operator commands and their observed results
  in the runbook only after the procedure passes these gates.

### Step 8. Container scenarios

| Scenario | Required observations |
|---|---|
| Form three servers from empty storage | Identical bootstrap configuration and server IDs on all three; one leader; replicated writes |
| Wipe and replace a server at the same name/address | New server ID; collision refused before authenticated removal; no second bootstrap or old-target snapshot accepted; new non-voter catches up, promotes, and retains state/identity after restart |
| Grow 3 to 5 and shrink 5 to 3 while a client publishes | Every committed registration survives; membership changes commit one at a time; new servers replicate before voting; all retained nodes converge |
| Automatic cleanup after promotion | Replacement has distinct name/address; failed member remains until grace and promotion commit; cleanup observes budget/minimum/quorum and persists across restart |
| Periodic cleanup and blocked cleanup | Removal without a join when limits permit; no removal below minimum or without reachable quorum; a returning peer resets failure eligibility |
| Leader removes itself | Authenticated removal commits; former leader reports removed and unready; retained voters elect one leader and preserve registrations |
| Removed-server campaigns and partition healing | Repeated campaigns cannot disrupt the live cluster; healing converges; genuine leader loss still permits failover |
| Lost-quorum recovery | Step 7's offline procedure and retained-state checks; excluded voters cannot reappear as the old cluster |

**Progress, 2026-10-24.** Scenario 2, wipe and replace at the same name and
address, is built and passed: `DockerServerReplacementTest` (`@Tag("docker")`).
- A follower is stopped, wiped and started empty, taking a new server ID. For 8
  seconds of observation the leader's configuration keeps the old ID as a voter
  and never lists the new one, so the join is refused, with the leader logging
  the old entry's replies under the new ID as ignored.
- `operator raft remove-peer`, run from the image on the cluster's network,
  with a wrong token exits 1 and changes nothing; with the right token it
  exits 0. The new server then joins as a non-voter and is promoted until all
  three are voters, the old ID among none.
- The replacement is ready and unfenced, holds `before-replace`, receives
  `after-replace` on all three servers, and keeps its ID and its vote across a
  restart.
- Support changes: the `three-node-acceptance` configurations carry the fixed,
  non-secret operator token `acceptance-operator-token`, because every server
  must hold the token; `networkOf` in `SharedDockerClusterFixture` is public;
  and the HTTP and cluster-state helpers of `DockerLostQuorumRecoveryTest`
  moved to the shared `DockerClusterApiHelper`.
- Verification: the first run passed in 41 s; five further consecutive runs
  passed in 39 to 42 s each; the refactored `DockerLostQuorumRecoveryTest`
  passed (2 tests, 84 s) and `DockerDeploymentContractTest` passed (20 tests).
  Every Maven capture is free of unflagged errors, and the replacement archive
  holds 12 ERROR headers across the six runs, all `RAFT_PEER_UNREACHABLE`
  flagged to the test, with 12 matching stack traces. `RAFT_PEER_NAME_UNRESOLVED`
  never appeared, so the test does not declare it.
- **The two missing observations, added the same day.** While the join is
  refused, the empty replacement must keep `lastLogIndex`, `commitIndex` and
  `snapshotLastIndex` at 0, hold no configuration and never lead. That shows it
  bootstrapped no second cluster and accepted no AppendEntries or snapshot that
  the leader sent to the old ID at its name. The leader's log must also show
  the replacement's replies ignored under the new ID. Five consecutive runs
  passed (40 to 44 s) with the added assertions.

Scenario 3, grow 3 to 5 and shrink 5 to 3 while a client publishes, is built and
passed five consecutive runs with the concurrent changes (40 to 45 s each): `DockerMembershipResizeTest` (`@Tag("docker")`).
- A client registers a new service every 200 ms from the first leader to the end.
- server4, then server5, start empty as detached containers on the cluster's
  network, each on its own volume, listing the cluster. Each first appears in
  the configuration as a non-voter, polled every 100 ms, and the leader logs its
  promotion; five voters follow. Every registration acknowledged by then is on
  all five servers.
- `operator raft remove-peer` removes server5 and then server4, each through
  server1. The configuration returns to the original three server IDs, all
  voters, on every retained server. The client's acknowledged registrations are
  all present on the three, and the client never failed to register.
- **A removed follower never learns of its removal.** In the second run of the
  test the removed servers were expected to report `removed`; they did not,
  and server5 was campaigning at term 31 after 30 seconds. That is the design
  of Step 6, not a defect: the leader stops replicating to a server once it is
  outside the configuration, so a removed follower keeps its old configuration.
  The test therefore asserts what the design promises: the three that remain
  keep one leader and an unchanged term for 10 seconds while the removed
  servers campaign. The campaigning servers' failed vote requests against each
  other at teardown are the one declared error, `RAFT_PEER_UNREACHABLE`; it was
  logged once in the five repeats and not in the others. An undeclared
  occurrence of it is what failed that second run, through the Docker-log audit.
- **A flake, found and fixed in the test.** In the third repeat of the first
  passing sequence, the assertion that the leader logged "Promoted server5 to
  a voter" failed although the archive holds the line (05:27:49.604 on server1).
  The leader logs a promotion when its commit completes, a moment after the
  configuration shows it, and the test read the container log once. It now
  waits up to 30 seconds. The same review made the final "every retained server
  agrees on three servers" check a bounded wait, since a follower may still be
  applying the second removal. No production code was involved.
- Verification: passed on its third version in 52 s; two further runs of the
  earlier version passed; the corrected version passed five consecutive runs
  (54 to 56 s each), with no unflagged error in any capture. Alongside it, the
  refactored `DockerServerReplacementTest` (42 s) and `DockerLostQuorumRecoveryTest`
  (2 tests, 90 s) passed again.
- **One change at a time, added the same day.** Sampled configurations cannot
  show it, because two quick commits can fall between two samples. The test now
  meets the rule head on: server4 and server5 start together, so the leader sees
  the second join and promotion while the first change may be uncommitted, and
  both removals are issued at once through different servers. A request refused
  as "already in progress" must say so and is repeated; any other refusal fails.
  Afterwards neither removal may have undone the other: the configuration stays
  the original three for 10 seconds. One such refusal occurred in the five
  passing runs, and the end state was right whether or not one occurred.

Scenario 6, a leader removes itself, is built and passed five consecutive runs:
`DockerLeaderRemovalTest` (`@Tag("docker")`, 27 to 29 s each).
- `operator raft remove-peer`, sent through a follower, names the current leader.
  The command exits 0, the former leader logs "stepping down ... its removal from
  the configuration has committed", and within 30 seconds it reports
  `removed: true`, is not ready and is no longer the leader.
- The two retained voters elect one leader between them, every retained server
  lists exactly those two, a registration made before the removal is on both,
  and a new one is accepted and replicated. For 10 further seconds the former
  leader does not lead again and the retained configuration does not change.
- No ERROR header was logged in any of the five runs' Docker archives.

Scenario 4, automatic cleanup of a failed voter after its replacement is
promoted, is built and passed four runs: `DockerFailedVoterCleanupTest`
(`@Tag("docker")`, 121 to 133 s each). A fifth run is still to do.
- With the acceptance servers' default policy (60 seconds of absence, a pass
  every 10 seconds, at least three voters), a follower is stopped. After the
  grace the leader logs `eligibleServerIds=[<id>], voters=3, removalBudget=1,
  minVoters=3` on each pass, yet for 25 further seconds the failed voter stays
  in the configuration, the cluster keeps one leader, and a write is accepted
  with two of three voters.
- server4, with its own name and address, joins as a non-voter, and the failed
  voter is still listed beside it. When the leader logs "Promoted server4 to a
  voter", the promotion's early pass finds `voters=4` and logs "Automatic
  cleanup committed removal of failed voter <id>: removals=1/1". The
  configuration is the two retained servers and server4, all voters, on every
  server.
- Both retained compose servers are then stopped and started in turn. The
  configuration does not change, and registrations from before the failure,
  during it and after the restarts are on all three.
- The first run failed its Docker-log audit, not its assertions: the leader's
  repeated `Failed to send InstallSnapshot chunk ... Unable to resolve host`
  errors to the stopped follower were not declared. The stopped follower is the
  deliberate cause, and the existing `RAFT_SNAPSHOT_TRANSFER_INTERRUPTED` entry
  matches the message, so the test declares it. Every ERROR header in the
  archives is flagged.

**Snapshot retry backoff, 2026-10-24.** The cleanup test showed a leader
sending a snapshot to a stopped lagging peer every 250 ms and logging an ERROR
with a stack trace each time. The delay was fixed. The logging was not changed,
at the owner's instruction; only the spacing of retries is. `SnapshotRetryBackoff`
now starts a peer's delay at 250 ms and doubles it with each consecutive
failure to that peer, to a cap of 5 seconds. A delivered snapshot, or the start
or end of a leadership, starts it over, and a snapshot rejected for persistence
backs off the same way. The assertion that a lost assembler restarts at chunk
zero at once is unchanged.
- Tests were written first. `SnapshotRetryBackoffTest` (9 tests, injected clock)
  covers the exact boundaries, the doubling and cap, reset on success and on
  clear, isolation between peers, 100,000 failures without overflow, and the
  argument checks. `RaftNodeSnapshotRetryBackoffTest` drives a leader with a
  peer whose snapshots fail, on the manual timer's clock: nothing is sent one
  millisecond before 250, 500 and 1000 ms have passed, one is sent when each
  has, and real time passing between opportunities does not count.
- Local check, 2026-10-24: those two classes, plus `InstallSnapshotTest`,
  `RaftNodeInstalledSnapshotRealRecoveryTest` and
  `RaftNodeOutboundSnapshotGenerationTest`, passed 32 tests; no unflagged error
  in the Maven capture or the application log.
- Gaps, stated plainly. The red run was not observed: the tests were not run
  against the old code, because the change would have had to be reverted in
  the working tree. The node-level test's first version could not have failed
  against the old fixed delay, since it only moved the manual clock, and was
  strengthened before its green run. The change has not run in `mvn install` or
  on Jenkins.

Scenarios 5 and 7 are built and passed their first local runs. They are
`DockerPeriodicCleanupTest` (a returning peer restarts its failure clock; a
failed voter is then removed by a periodic pass with no join: five voters,
`removalBudget=2`, `minVoters=3`, four voters remain) and
`DockerRemovedServerCampaignTest` (a partitioned follower is removed, campaigns
at higher terms, and on return changes nothing; killing the leader still lets
the other three elect another).
- **Leadership churn when a stopped voter returns (found by the first
  periodic run, not acted on).** The leader's channel to the restarted voter was
  in gRPC reconnect backoff, so the voter heard no heartbeat and ran four
  elections (terms 2 to 5). Stickiness ignored its campaigns, but the next
  AppendEntries that reached it was answered with term 5 and the leader stepped
  down on that higher term; two more elections followed. This is the cost of
  choosing stickiness over Pre-Vote (section 5): a brief leadership change, no
  data loss. Options are Pre-Vote, or a shorter channel reconnect backoff; the
  owner has not asked for either.
- Because any voter can win an election, the cleanup tests now read the logs of
  every server, compose and detached (`DetachedServer.logs()`,
  `DockerClusterApiHelper.clusterLogs`), not only the three compose servers'.

**Jenkins builds 34 and 35, 2026-10-24.**
- Build 34 (91 s) failed one default-suite test, the guard that every Docker
  class declaring `RAFT_PEER_UNREACHABLE` also declares
  `RAFT_PEER_NAME_UNRESOLVED`. I had removed the second from two classes because
  it had not been observed; it is restored in both. The end-to-end and Docker
  stages were skipped.
- Build 35 (1,288 s): default 1,148 passed, end-to-end 10 passed, Docker 38 of
  40 passed. The two errors: `DockerPeriodicCleanupTest` timed out reading the
  wrong logs (server5, a detached server, was the leader; fixed as above), and
  `DockerLeaderRemovalTest` failed at 3.5 s because the daemon's image build
  failed: "failed to prepare extraction snapshot ... parent snapshot ... does
  not exist: not found". The next class built the same image one second later.
- Root cause of the image build failure: not found. No other Jenkins job was
  running (PeeGeeQ last ran 2026-10-11, PeeGeeQ-Cache 2026-09-25, no executor
  busy), and the pipeline never prunes images. The failing step shows buildkit
  marking a layer `CACHED` while the snapshot it needs is gone, which is a
  daemon cache fault. It cannot be removed from the repository, so
  `buildImageRetryingAMissingSnapshot` tries that one failure once more, logs the
  first failure as a warning, keeps it as suppressed if the second also fails,
  and rethrows anything else at once. Written test first
  (`SharedDockerClusterImageBuildTest`, 5 tests): red against a stub with the
  two retry tests failing on behaviour, then green; 61 tests with the fixture
  guards. If the same failure recurs, look at the daemon (Docker version,
  storage driver, containerd image store), not at the tests.

- Still to do: three consecutive runs of every Step 8 scenario against a fresh
  image from the final jar, a green Jenkins run with all stages, and the full
  suites. This is the exit gate.

**Exit gate.** Every scenario above passes three consecutive runs against a
fresh image from the final runtime JAR. Default,
end-to-end, and Docker suites pass on the same final source/dependency state;
retain timestamped logs using `docs/TESTING.md`. Complete the prohibited-framework,
environment-configuration, timeout-API, source-header, and `git diff --check`
audits. Update the design, feature validation, and runbook with actual evidence
and remaining limitations before recording completion of this list.

### Document review corrections (2026-10-03)

The six review findings are addressed in the documents, without claiming new
runtime behavior or new suite results:

- The storage runbook restricts same-identity restore to the last durable state;
  older backups and lost quorum have explicit support boundaries.
- Legacy WAL/catalog byte compatibility is separated from whole-node upgrade
  compatibility, and storage examples use the versioned JSON contract.
- Step 5 defines distinct-name/address automatic replacement and authenticated
  same-name/address replacement without join-driven eviction.
- Steps 5 to 8 now have configuration, sequencing, mutation, real-storage, and
  container exit gates. Step 4 remains open for its recorded mutation evidence.
- `OPEN_SOURCE_USAGE.md` inventories the current direct dependencies and their
  version sources, removing the obsolete prohibited-framework entry.
- `docs/TESTING.md` and `docs/PROJECT_STANDARDS.md` use the same visible-terminal,
  timestamped `logs/` command convention.

## 7. Records outside the membership work

These were recorded in this list because it was the current list at the time.
They were moved here from between the Step 4 records on 2026-10-05; their
text is otherwise unchanged.

### RaftLog 1.5.0 (2026-10-11)

Recorded here beside the upgrade before it. The work is Phase 6 of
[`task-list-single-pom-and-quorus-removal-2026-10-21.md`](../docs/archive/task-list-single-pom-and-quorus-removal-2026-10-21.md),
which has the whole record.

- **Change.** The root POM pins `raftlog.version` 1.5.0, up from 1.4.1. The
  WAL and metadata formats are unchanged. Design section 14.7 names the new
  version.
- **1.5.0 against Qraft's use of it.** Its production code differs from 1.4.1
  by one addition: `compactionBoundary()` on the `RaftStorage` interface, as a
  default method that answers 0, which `FileRaftStorage` overrides with the
  method it already had. Nothing Qraft called has changed.
- **What Qraft does with it.** `RaftPersistence.compactionBoundary` calls the
  interface; its test of the class `FileRaftStorage` is gone. This is in the
  `raft` package and not in `RaftNode`.
- **Verified.** Jenkins build 19, on `f9b3403`: 927 default tests with every
  coverage gate met, 10 end-to-end, 32 Docker and slow, and no ERROR event or
  uncaught exception without a flag in its 141 log files. The real-storage
  recovery tests are among them.

### RaftLog 1.4.1 (2026-10-02)

- **Change.** The root POM pins `raftlog.version` 1.4.1, up from 1.4.0. The
  WAL and metadata formats are unchanged. Design section 14.7 names the new
  version.
- **1.4.1 against Qraft's use of it:**
  - The removed `FileRaftStorage(boolean)` constructors: Qraft already builds
    the storage from `RaftStorageConfig` everywhere.
  - `updateMetadata` refuses a null vote: Qraft passes an `Optional`.
  - Narrower torn-tail repair, `LogEntryData` compared by content, and the
    inferred compaction boundary: covered by the storage contract tests
    below, which pass unchanged.
- **Defect in the build, found by this upgrade.** After `mvn install`, the
  runtime JAR still held RaftLog 1.4.0 classes. `qraft-controller` shades its
  dependencies into its own jar; without `clean`, the shade step starts from
  the previous shaded jar, whose copy of a dependency wins over the new one.
  Qraft's own classes were current, so earlier Docker results stand for Qraft
  code. `docs/TESTING.md` now says to run `mvn clean install` after a
  dependency change. The cause was fixed the next day; see "One executable
  jar" below.
- **Validation,** after `mvn clean install`:
  - 812 tests, every coverage gate met, including the contract tests of
    design section 14.7: `RaftLogStorageIntegrationTest`,
    `RaftNodeRealStorageRecoveryTest`, `RaftNodeRealSnapshotRecoveryTest`,
    `RaftNodeInstalledSnapshotRealRecoveryTest`, `FileSnapshotStoreTest`, and
    `RaftStorageProcessLockTest`.
  - The runtime JAR reports `raftlog-core` 1.4.1.
  - The Docker suite on that JAR: 23 of 23.
  - The end-to-end suite was not rerun.

### One executable jar (2026-10-03)

The fix for the build defect above.

- **`qraft-controller` no longer shades.** Its jar holds only its own
  classes. Nothing used its executable jar: the design makes
  `qraft-runtime` the one deployment artifact.
- **`qraft-runtime`'s shade step** now merges `META-INF/services` files and
  drops jar signatures, which the controller's step had done for it. Its own
  jar is rebuilt on every build (`forceCreation`), so the shade step never
  starts from its previous output.
- **Two faults the controller's fat jar had hidden,** both fixed:
  - **Logback was missing.** The root POM gives every module Logback in test
    scope, which kept it out of the runtime's own dependencies. The runtime
    now declares it in runtime scope.
  - **Mixed Prometheus exporter versions.** The runtime resolved
    `opentelemetry-exporter-prometheus` 1.48.0-alpha and Prometheus 1.3.6
    through the instrumentation BOM, while the controller compiled and
    tested against 1.59.0-alpha and 1.3.10. The old executable jar held
    classes of both. The root POM now pins 1.59.0-alpha for every module.
- **Proof.** With the new poms and no `clean`: a build on RaftLog 1.4.0
  packaged 1.4.0, and a rebuild after changing the version to 1.4.1
  packaged 1.4.1.
- **The new jar against the old one.** It adds nothing. It drops only the
  second Prometheus copy (the 1.3.6 protobuf exposition format and its
  bundled protobuf, 794 classes) and seven annotation classes. The gRPC and
  SLF4J service files are identical, and the jar is 23.5 MB, down from
  25.6 MB.
- **Validation,** on a build without `clean`:
  - `mvn install`: 812 tests, every coverage gate met.
  - The Docker suite: 23 of 23. The end-to-end suite: 7 of 7.
  - The packaged jar starts and configures Logback from its own
    `logback.xml`.

## 8. Out of scope

- Client membership, which stays outside Raft (design principle 8).
- Enterprise-style features: redundancy zones and automated upgrade migrations.
  They can build on non-voters later.
- Multiple datacenters.
- The maximum-term question in the predecessor list's section 6. It stays with
  that list.
