# Task List: Raft Membership Changes

**Date:** 2026-09-29
**Active work:** Step 4, joining, and operator list and remove. It is built,
and the full suites passed on 2026-10-02; its mutation evidence is outstanding
(Step 4 record). Steps 1 to 3 were done
2026-09-29. Qraft adopts Consul's membership model; every decision in section
5 is made.
**Last updated:** 2026-10-03 (document review corrections and remaining-step contracts)
**Predecessor:** [`task-list-test-suite-remediation-2026-09-27.md`](../docs/archive/task-list-test-suite-remediation-2026-09-27.md),
archived 2026-10-02. Its packaged-artifact test waits for the admin interface.
**Paused:** [`task-list-embedded-admin-interface-2026-09-27.md`](task-list-embedded-admin-interface-2026-09-27.md), after its Step 1
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

This concerns server membership only. Agents never join the Raft membership
(design principle 8), and their registration and expiry are unchanged.

## 2. Current state

Checked in the code on 2026-09-29.

- **Membership is fixed at startup.**
  - Each server parses its `clusterNodes` setting, for example
    `controller1=host1:9080,controller2=host2:9080,controller3=host3:9080`,
    into the member IDs and peer addresses
    ([`QraftControllerService.java:100-113`](../qraft-controller/src/main/java/dev/mars/qraft/controller/QraftControllerService.java#L100-L113)).
  - `RaftNode` copies the set once
    ([`RaftNode.java:349`](../qraft-controller/src/main/java/dev/mars/qraft/controller/raft/RaftNode.java#L349))
    and never changes it.
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
  be removed with `consul operator raft remove-peer`.

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

- Red before green. A test for existing behaviour that passes at once is shown
  to fail against a mutation that removes the behaviour, and the mutation is
  recorded. Mutations run only in the isolated copy.
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
Written test first; not yet run:
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

### RaftLog 1.4.1 (2026-10-02)

Not part of the membership work; recorded here because this is the current
list.

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

### Step 5. Failed-server cleanup

**Status: Planned; not implemented.** The contract below was specified during
the document review on 2026-10-03. Current server JSON rejects these new settings;
the promotion settings still exist only on `RaftNode.Builder`.

#### Configuration contract

Add the following settings under `server.raft.membership`, validated before any
runtime resource opens. Existing files without this object retain the current
promotion defaults and receive the cleanup defaults when Step 5 is implemented.

| Setting | Planned default | Validation and meaning |
|---|---|---|
| `promotionStabilizationMs` | 10000 | Integer, nonnegative; maps to the existing builder option |
| `promotionMaxTrailingEntries` | 250 | Integer, nonnegative; maps to the existing builder option |
| `cleanup.enabled` | true | Boolean; disabling cleanup does not disable authenticated operator removal |
| `cleanup.failureTimeoutMs` | `max(60000, electionTimeoutMs)` | Positive integer, at least `server.raft.electionTimeoutMs`; continuous absence of contact before a voter is eligible |
| `cleanup.intervalMs` | `max(10000, heartbeatIntervalMs)` | Positive integer, at least `server.raft.heartbeatIntervalMs`; periodic cleanup cadence |
| `cleanup.minVoters` | 3 | Positive integer; automatic cleanup must not leave fewer voters; a smaller existing cluster remains valid and is not expanded automatically |

Unknown fields, wrong JSON types, and values whose conversion to heartbeat
rounds would overflow are refused. Durations use ceiling division by the
heartbeat interval and are measured in applied heartbeat rounds, as promotion
and check-quorum already are. A delayed loop must not turn elapsed wall-clock
time into fictitious missed rounds. Expose parsed values through `AppConfig`
and wire them through `QraftControllerService` to the node; do not read JVM or
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
  settings, and a controller lifecycle test proves wiring of non-default
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
- New behavior is red before green; safety guards have recorded mutation
  evidence. Changed concurrency tests pass five consecutive focused runs;
  `mvn install` and the fresh-image Docker suite pass. The real replacement
  and cleanup scenarios remain mandatory in Step 8 before the list closes.

### Step 6. Protection from disruptive servers

Leader stickiness was built on 2026-10-02 (see "Review remediation" under
Step 4), with unit tests for a removed server and for the contact-expiry
boundary. Still open: the test of a server rejoining after a partition, the
container scenarios, and mutation evidence.

Leader stickiness is the selected protection (thesis section 4.2.3). Do not add
a second election protocol merely to close this validation step.

**Exit gate.** Manual-timer tests repeatedly campaign a removed server and
heal a partition containing a higher-term, stale-log server. They prove a
working leader and its followers keep their term during the contact lease,
the exact expiry boundary permits voting, an isolated leader steps down via
check-quorum, and genuine failover and catch-up still complete. Record mutations
of both the leader and follower guards, lease expiry, and check-quorum behavior.
Run changed concurrency tests five consecutive times and pass `mvn install`.
Step 8 must repeat disruption and partition healing over real transport in
containers before the list closes.

### Step 7. Recovery from lost quorum

**Status: Planned; no supported recovery procedure exists today.** An older
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
explicitly implements and tests it.

**Exit gate.**

- Real-storage/process tests reject malformed or duplicate identities,
  incompatible data, conflicting recovery inputs, and ordinary startup that
  would silently recover without the explicit offline action.
- Crash/interruption tests cover each durable publication boundary, exclusive
  locking, and safe repeat invocation; no partial rewrite is accepted as a
  successful recovery.
- A container scenario loses the majority, fences the excluded servers,
  recovers selected survivors, elects one leader, and verifies the documented
  retained catalog state and subsequent writes. Excluded servers return only
  through the documented replacement/join path.
- If whole-cluster backup restore is delivered, its own scenario uses backups
  that predate later participation and proves the recovered history and voter
  isolation. Otherwise both the design and runbook keep that feature open.
- Safety guards have mutation evidence, focused concurrency tests pass five
  consecutive runs, and the full default, end-to-end, and fresh-image Docker
  suites pass. Publish executable operator commands and their observed results
  in the runbook only after the procedure passes these gates.

### Step 8. Container scenarios

| Scenario | Required observations |
|---|---|
| Form three servers from empty storage | Identical bootstrap configuration and server IDs on all three; one leader; replicated writes |
| Wipe and replace a server at the same name/address | New server ID; collision refused before authenticated removal; no second bootstrap or old-target snapshot accepted; new non-voter catches up, promotes, and retains state/identity after restart |
| Grow 3 to 5 and shrink 5 to 3 while an agent publishes | Every committed registration survives; membership changes commit one at a time; new servers replicate before voting; all retained nodes converge |
| Automatic cleanup after promotion | Replacement has distinct name/address; failed member remains until grace and promotion commit; cleanup observes budget/minimum/quorum and persists across restart |
| Periodic cleanup and blocked cleanup | Removal without a join when limits permit; no removal below minimum or without reachable quorum; a returning peer resets failure eligibility |
| Leader removes itself | Authenticated removal commits; former leader reports removed and unready; retained voters elect one leader and preserve registrations |
| Removed-server campaigns and partition healing | Repeated campaigns cannot disrupt the live cluster; healing converges; genuine leader loss still permits failover |
| Lost-quorum recovery | Step 7's offline procedure and retained-state checks; excluded voters cannot reappear as the old cluster |

**Exit gate.** Every scenario above passes three consecutive runs against a
fresh image from the final runtime JAR. Each safety assertion is shown able to
fail against a recorded targeted mutation in the isolated copy. Default,
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

## 7. Out of scope

- Agent membership, which stays outside Raft (design principle 8).
- Enterprise-style features: redundancy zones and automated upgrade migrations.
  They can build on non-voters later.
- Multiple datacenters.
- The maximum-term question in the predecessor list's section 6. It stays with
  that list.
