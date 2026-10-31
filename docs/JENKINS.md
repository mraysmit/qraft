# Qraft on Jenkins

The [Qraft job](http://192.168.137.32:8080/job/Qraft/) runs every test suite. Select **Build Now**
to run the pipeline; it has no recurring trigger.

## Readiness verified, 2026-10-17

The existing job was checked on the live Jenkins server at the user's request.
It is enabled, buildable and idle, loads `Jenkinsfile` from this repository's
`main` branch, and has no recurring trigger. Jenkins's declarative pipeline
validator accepted the current `Jenkinsfile`. No job or global server settings
needed changing, and no build was started for this readiness check.

The online Linux x64 built-in worker has the required `linux` label. Read-only
tool checks under the Jenkins account confirmed cached SapMachine JDK 27+35,
`javac` 27, Maven 3.9.16 using that JDK, Git 2.43.0, Bash, curl, Docker client
and engine 29.1.3, and Docker Compose 2.40.3. The workspace is
`/var/lib/jenkins/workspace/Qraft`; its existing JDK and Maven caches match the
paths configured by the pipeline.

To run a build, select **Build Now** on the job page. On this workstation, the
existing local helper can also be run in the visible VS Code terminal:

```powershell
.\.env\qraft-jenkins-build.ps1
```

The helper reads the existing API token without printing it, starts the job
with mutation testing off, follows its status, and retains its console and
archived artifacts under `logs/jenkins/Qraft-<build>/`. The helper and token
are local files, not part of the repository; the job page is available without
that helper. Audit the retained Maven, application, subprocess and Docker logs
under the project's logging policy before accepting a run.

Jenkins checks out pushed `main`, not this workstation's working tree. At this
check, GitHub `main` and local `HEAD` both pointed to `bef4637`; the uncommitted
promotion-boundary tests and documentation edits were not published and would
not be included in a Jenkins build.

## Build 27 verification, 2026-10-17

[Build 27](http://192.168.137.32:8080/job/Qraft/27/) ran at the user's request
against pushed `main`, commit `bef4637`, and succeeded in 10 minutes 12 seconds.
The default suite passed 1,024 tests and every coverage gate; end-to-end passed
10 tests; Docker passed 32 tests on the newly built jar and rebuilt image.
Jenkins and the retained Surefire XML agree on 1,066 results, with zero failures,
errors or skips. All 64 membership-parser cases passed. The two uncommitted
promotion-boundary tests prepared on this workstation were not included.

All 860 Jenkins artifacts were downloaded under `logs/jenkins/Qraft-27/archive/`,
with the complete console retained beside that directory. The final audit
reviewed 133 log files, 145 Surefire text reports and four JSON configuration
documents. All 648 error headers and 480 exception headers were attributed,
including one explicitly flagged lock-contender uncaught exception. Counts
include duplicate captures. The flagged native Tempo WAL startup event had a
later recovery with the same tenant, remote name and endpoint. No unflagged
error, unattributed exception trace or ANSI escape remained. Candidate
classification details and the file inventory are retained in
`logs/jenkins/Qraft-27/log-audit.json`; suite totals, revision and artifact
verification are in `logs/jenkins/Qraft-27/verification.json`.

This verifies the committed parser slice and the source at `bef4637`. Membership
runtime wiring, automatic cleanup and the uncommitted duration-boundary tests
remain separate work.

## Duration-boundary replay verification, 2026-10-19

The duration-conversion work was tested without committing or pushing the
working tree. Replays of build 27 checked out `bef4637`, verified that base
revision, and applied an archived patch containing only production and test
source changes. The normal job definition still loads the repository's
`Jenkinsfile` from `main`; no recurring trigger was added. A later ordinary
**Build Now** uses pushed source and will not include this patch until committed
and published.

- [Build 28](http://192.168.137.32:8080/job/Qraft/28/): 72 focused tests, one
  promotion-overflow assertion failure and one timeout. Investigation found
  the command helper could read the next log index before the leadership
  no-op appended and acknowledge that no-op instead of the submitted command.
  A state-loop barrier now drains the queued no-op before the index is read.
  No timeout was increased and no error allowance was added.
- [Build 29](http://192.168.137.32:8080/job/Qraft/29/): 74 focused tests, exactly
  three RED assertions and zero test errors. They demonstrated promotion and
  quorum-grace ceiling-division overflow, plus election-jitter overflow into a
  negative scheduled delay. Production was unchanged for this run.
- [Build 30](http://192.168.137.32:8080/job/Qraft/30/): successful in 11 minutes
  25 seconds. Five consecutive focused runs passed 74 tests each, then the
  default suite passed 1,028 tests and every coverage gate; end-to-end passed
  10 and fresh-image Docker passed 32. The three full suites total 1,070 tests;
  Jenkins publishes 1,440 results including the 370 focused repeat executions.
  There were zero failures, errors or skips in this successful run.

Build 30's verified source patch SHA-256 is
`efd9946afd383132a141cb1ec4d7cad95a86145c83ad06c8d18a3c0aae10c33d`.
The patch and individual source checksums are archived as
`logs/verification-source.patch` and `logs/verification-source.sha256`.
At completion of that slice, local source matched those checksums after CRLF normalization.
The production change replaces the two overflow-prone ceiling divisions with
`Math.ceilDiv` and limits election jitter to the available `long` headroom.

All 892 artifacts are retained locally under `logs/jenkins/Qraft-30/archive/`,
with console, test totals and the final audit beside that directory. The audit
reviewed 143 log files, 155 Surefire text reports and four JSON configuration
documents. All 645 error headers and 468 exception headers were attributed,
including the explicitly flagged lock-contender uncaught exception. Counts
include duplicate captures. The flagged native Tempo startup event had a later
WAL recovery matching its tenant, remote name and endpoint. No unflagged error,
unattributed trace or ANSI escape remained. Earlier failed captures are retained
separately as diagnosed failure evidence, not included in the successful audit.

Replay pipelines and source snapshots are retained in
`logs/jenkins/membership-rounds-2026-10-19/`. This completes the timing slice;
membership settings wiring and automatic failed-voter cleanup were still open
at that checkpoint. The subsequent wiring slice is recorded below.

## Membership-wiring replay verification, 2026-10-19

The wiring slice was verified through a source-patch replay of build 27 on
`bef4637`, including the earlier timing changes. No commit or push was made.

- [Build 31](http://192.168.137.32:8080/job/Qraft/31/) established RED: 91 cases,
  exactly seven missing-policy-log assertion failures, zero test errors, and
  no unrelated application-log errors. The failed console, reports, source
  snapshot and diagnosis are retained separately.
- [Build 32](http://192.168.137.32:8080/job/Qraft/32/) succeeded in 11 minutes
  22 seconds. Five consecutive focused runs passed 115 cases each. The default
  suite passed 1,059 tests and every coverage gate; end-to-end passed 10 and
  fresh-image Docker passed 32. The full suites total 1,101 executions;
  Jenkins publishes 1,676 results including the 575 focused repeats. There
  were zero failures, test errors or skips.

The tested source patch SHA-256 is
`23a70bff66e1e3a3abc9f1778447e389ea8b034a6237d23ea29897727403da54`.
All seven production/test source files match the archived source checksums
after CRLF normalization, including the two new policy files. The patch and
manifest are archived under `logs/verification-source.*`. Replay pipelines,
source snapshots and local verification helpers are retained in
`logs/jenkins/membership-wiring-2026-10-19/`.

All 916 artifacts are retained under `logs/jenkins/Qraft-32/archive/`, with
the console, verification summary and final audit alongside it. The audit
reviewed 141 textual log streams, including four JSON logs, 166 Surefire text
reports and four JSON configuration documents. Two binary `raft.log` files
are WAL data, not diagnostic streams. All 849 error headers, 518 exception
headers and one uncaught-exception header were attributed. Counts include
duplicate captures. The single native Tempo startup error in its original
capture was explicitly flagged and followed by matching WAL recovery at
line 56 after the error at line 52. No unflagged error, unattributed trace or
ANSI escape remained.

`QraftServerService` now passes all six values into the node's immutable
`RaftMembershipPolicy`, and logs its effective values without the operator
token. Promotion uses that policy. Seven real-server cases verify HTTP
liveness, policy logging and WAL release after shutdown. Cleanup settings
are retained, but startup explicitly reports `cleanup.active=false` until
automatic removal is implemented. That is the next task; the overall Step 5
exit gate remains open. The normal SCM job still reads `Jenkinsfile` from
pushed `main`, so an ordinary **Build Now** does not include these uncommitted
changes.

## Lost-quorum recovery verification, 2026-10-24

[Build 33](http://192.168.137.32:8080/job/Qraft/33/) built pushed `main` at
`55fbb1c` with `MUTATION_TESTING=off` and succeeded in 718 seconds. The default
suite passed 1,138 tests and every coverage gate, end-to-end passed 10 and
fresh-image Docker passed 34, which includes the two scenarios of
`DockerLostQuorumRecoveryTest` (recovery from current directories and from a
backup). There were no failures, errors or skips.

The retained archive is under `logs/jenkins/Qraft-33/`. Its `logs/` folder holds
664 error headers, all flagged as intentional or injected, and no stack trace
under an unflagged header at any level. The job needs the `MUTATION_TESTING`
parameter, so a plain `POST /job/Qraft/build` answers 400; `.env/qraft-jenkins-build.ps1`
starts it with `buildWithParameters`.

## Builds 34 and 35, 2026-10-24

[Build 34](http://192.168.137.32:8080/job/Qraft/34/) failed in 91 seconds on one
default-suite test, the guard that a Docker test class which makes a peer
unreachable also declares that its name may not resolve. The end-to-end and
Docker stages were skipped. [Build 35](http://192.168.137.32:8080/job/Qraft/35/)
ran every stage in 1,288 seconds: default 1,148 and end-to-end 10 passed, and
Docker passed 38 of 40.

One of the two Docker errors came from the host: the image build failed with
"failed to prepare extraction snapshot ... parent snapshot ... does not exist:
not found", and the next class built the same image a second later. No other job
was running and the pipeline does not prune images, so the cause was not found;
the fixture then tried that one failure once more. That retry was removed on
2026-10-27 at the owner's direction: a lost snapshot points to a deeper fault, which
must fail the build and be investigated rather than be retried away. Its unit test
had also left two unflagged `#13 ERROR` lines in every default run since build 36.
If the failure recurs, check the Docker daemon's version, storage driver and image
store on the node.

[Build 36](http://192.168.137.32:8080/job/Qraft/36/), on `3b3fe38`, passed every
stage in 1,196 seconds: default 1,153, end-to-end 10 and Docker 40, with every
coverage gate met. Its archived logs hold 631 error headers, all flagged, and no
unflagged stack trace.

[Build 37](http://192.168.137.32:8080/job/Qraft/37/), on `888892e`, failed in 1,156
seconds. Default (1,153) and end-to-end (10) passed and all six Step 8 classes
passed, but `DockerClientTopologyTest` failed its Docker-log audit on one
undeclared error: a vote response refused while a stopped server's transition
sequencer was draining. Handlers disagreed about such a refusal (six logged it at
debug, five at error). One class now treats it the same everywhere: debug, and
any other failure stays an error. No declaration excuses it any more.

[Build 38](http://192.168.137.32:8080/job/Qraft/38/), on `adecfbc`, passed every stage
in 1,178 seconds: default 1,164, end-to-end 10 and Docker 40, every coverage gate
met, 675 error headers in the archived logs all flagged and no unflagged stack
trace. It is the first green run after build 37.

[Build 39](http://192.168.137.32:8080/job/Qraft/39/), on `ffdec45`, passed every stage
in 1,199 seconds: default 1,164, end-to-end 10 and Docker 40, every coverage gate
met, 643 error headers in the archived logs all flagged, no unflagged stack trace,
and no error that mentions a draining sequencer. It is the first of the three
consecutive green runs required on the final drain-handling code.

[Build 42](http://192.168.137.32:8080/job/Qraft/42/), on `2dcb2ec`, the first run with
Pre-Vote, failed on the Docker stage (default 1,189 and end-to-end 10 passed; Docker
41 tests, 3 errors). Two Docker tests still proved a campaign by a rising term, and
fixing them found a cut-off follower that kept reporting ready; the third was
fallout from the first.

Builds [43](http://192.168.137.32:8080/job/Qraft/43/),
[44](http://192.168.137.32:8080/job/Qraft/44/) and
[45](http://192.168.137.32:8080/job/Qraft/45/), all on `9c946ac`, are the three
consecutive green runs of the Pre-Vote exit gate. Each passed every stage in about
1,280 seconds: default 1,190, end-to-end 10 and Docker 41, every coverage gate met.
Their archived logs hold 651, 627 and 722 ERROR lines; every one carries an
intentional-error or injected-failure flag, no stack trace is unflagged, and no
error mentions a draining sequencer.

The follow-up that resets a peer's gRPC reconnect backoff when it sends a vote or
pre-vote request (`RaftTransport.peerHeardFrom`, `7136209`) was verified by builds
[46](http://192.168.137.32:8080/job/Qraft/46/),
[49](http://192.168.137.32:8080/job/Qraft/49/) and
[55](http://192.168.137.32:8080/job/Qraft/55/), all on `7136209`. Each passed every
stage: default 1,196, end-to-end 10 and Docker 41, every coverage gate met. Their
archived logs hold 705, 694 and 663 ERROR lines, all flagged; no stack trace is
unflagged and none mentions a draining sequencer. `DockerReturningVoterTest`, which
now stops a follower for 200 seconds and requires it ready within 15 seconds of
answering, took 260 to 284 seconds.

Builds 47, 48 and 50 to 54, on the same commit, are not results for the code. 47, 50,
51, 52 and 53 failed before any test ran, in the checkout or the Jenkinsfile fetch
(`git fetch` stalled or was reset by github.com). 48 and 54 passed the default and
end-to-end suites and failed in the Docker stage only because the image build could
not reach Docker Hub (a token read timeout in 48; a DNS lookup of
`registry-1.docker.io` through the host's local stub in 54, which failed 7 tests).
On the Jenkins host, 2 of 6 `git ls-remote` calls to github.com hung for 25 seconds
and downloads from GitHub ran at 7 to 55 KB/s while Cloudflare ran at 16 MB/s; 1 of
25 DNS lookups through the gateway (192.168.137.1) failed where none failed through
1.1.1.1. The link itself showed no errors. The checks were clean again when build 55 ran on 2026-10-26, and the owner then
fixed the Jenkins host's network (what was changed was not recorded). If it
recurs, look at the gateway's DNS and its route to GitHub before suspecting the
code.

## Local run in place of Jenkins, 2026-10-27

On the `markr` workstation no Jenkins could run the job: 192.168.137.32 did not
answer, and the local `jenkins/jenkins:lts-jdk21` container has no Qraft job, no
`linux` label, no Docker CLI and no `mvn` on its path. At the owner's direction the
three suites ran locally instead, against Docker Desktop, with JDK 27 and Maven
3.9.16, each through `Tee-Object` in a visible console and with the Jenkinsfile's
commands. The one difference: the Docker stage passed `-Dgroups=docker,slow`,
because `mvn.cmd` runs through `cmd.exe`, which reads a `|` as a pipe. Surefire
reads the comma list as "any of", so the same classes ran.

The first run, on `7a1ed98` (client list Phase 1), passed every suite (default
1,210, end-to-end 10, Docker 41) but its audit found two unflagged `#13 ERROR`
lines, from the image-build retry's unit test (see builds 34 and 35). The retry
and that test were removed. The second run, on `7a1ed98` with that removal in the
working tree, passed default 1,205 with every coverage gate met, end-to-end 10 and
Docker 41 (24 min 36 s, the image built once on a fresh build), with no failures,
errors or skips. Its 223 retained logs, including 19 Docker archive folders, hold
823 ERROR lines, all flagged, 559 stack traces, none under an unflagged header,
and no JVM crash file.

The third run, with the client list's Phase 2 client slice in the working tree
(later `6680a67`), passed default 1,221 and end-to-end 10 but failed two Docker
classes, each a defect:

- `DockerStartCommandsTest`: the start scripts ran `docker compose up -d` without
  `--build`, so they started images left from an earlier build, whose client still
  read `client.id`. The client never became ready, and its errors were unflagged.
  Every start command now passes `--build` (`14ded7d`, test first,
  `DockerDeploymentContractTest.everyStartCommandRebuildsItsImagesBeforeStartingThem`).
- `DockerServerReplacementTest`: a check-quorum step-down moved leadership while
  the leader was sending a snapshot to the old `server3`. The wiped server's answer
  came from another server ID and was ignored, but the transfer was never ended,
  and removing the old server did not forget it, so the leader never sent the new
  `server3` anything for the rest of the term. Such an answer now ends the
  transfer and backs off, and a server leaving the configuration takes its
  snapshot transfer and backoff with it (`f991254`, test first,
  `RaftNodeServerIdCountingTest` and `SnapshotRetryBackoffTest`). What starved
  the leader of `server1`'s answers for the 1.5 seconds of check-quorum is not
  explained: no Raft RPC has a deadline, so a stalled call logs nothing.

The fourth run, on `14ded7d` with the snapshot fix in the working tree, passed
default 1,225 with every coverage gate met, end-to-end 10 and Docker 41 (26 min
5 s), with no failures, errors or skips. Its 234 retained logs hold 910 ERROR
lines, all flagged, 588 stack traces, none under an unflagged header, and no JVM
crash file. No leader stepped down, so the replacement test did not take the
path the fix covers; the unit tests are its evidence.

Where the Docker stage's time goes, from the fourth run's log: the classes run one
at a time (`@ResourceLock("shared-docker-clusters")`, and no parallel execution is
configured), and their times add up to the stage's (1,550 s of 1,565 s).

| Part | Seconds |
|---|---|
| Five tests that wait for the cluster's timers: `DockerReturningVoterTest` (a follower stopped for 200 s, then a 40 s hold), `DockerPeriodicCleanupTest` (two 40 s absences, then the 60 s cleanup grace), `DockerLostQuorumRecoveryTest`, `DockerFailedVoterCleanupTest` (the 60 s grace) and `DockerRemovedServerCampaignTest` | 722 |
| `DockerStartCommandsTest`, the real start scripts one after another, the longest being the observability cluster (63 s) and stack (43 s) | 300 |
| Starting 23 compose clusters until ready (137 s) and removing them (44 s) | 181 |
| The test bodies of the other ten classes | about 350 |

Image builds take about 23 s of it (the fixture's one build, 7 s, and six cached
builds from the start scripts' `--build`); container creation, server start
(about 1 s) and server shutdown (under 0.3 s) are not where it goes. The four
runs took 28 min 57 s, 24 min 36 s, 31 min 1 s (with the replacement test
waiting out its limit) and 26 min 5 s. The records of builds 43 to 45 give whole
pipelines of about 1,280 s, which looks short beside these figures for the same
classes; without their stage times, the two cannot be compared.

On 2026-10-28 two more runs, on `f991254`, verified deadlines on every Raft call
(`GrpcRaftTransport`: 10 s for a vote, pre-vote or append, first 30 s for a
snapshot chunk). Without one, a call on a connection that died without a reset never
failed and logged nothing, and a snapshot chunk stuck that way kept its transfer
in place, so the leader sent that follower no snapshot for the rest of the term.
`GrpcRaftTransportTest` shows an unanswered call failing with
`DEADLINE_EXCEEDED` at its deadline (red first), and each call's deadline
counting from when it is sent.

A check against Consul's Raft transport (`hashicorp/raft`, with Consul's
`Timeout: 10 * time.Second`) then brought two changes, each red first. A snapshot
chunk now gets Consul's deadline, 10 s for every whole 256 KiB and never less, so
40 s for a full 1 MiB chunk. And where Consul closes a connection whose call failed,
gRPC ends only the call and keeps sending later calls into a dead connection, so
the transport's connections now use keepalive: a ping after 10 s without a read,
gRPC's minimum, and the connection is closed when it is not answered within 10 s.
`GrpcRaftServer` permits those pings every 5 s, with or without a call; gRPC's
default would close the connections with `too_many_pings`. `GrpcRaftKeepaliveTest`
(tagged `slow`) shows a connection that stops answering being closed and the peer
reached on a new one, and an idle connection staying up through its pings.

The first run passed default 1,228 and end-to-end 10 and failed three Docker
classes, none on the deadlines. Each failure was traced to its cause:

- `DockerLostQuorumRecoveryTest` logged `Failed to send InstallSnapshot chunk 1/1
  to server1: UNAVAILABLE: io exception` three times. Its offline backup stops
  server1, and the new leader had compacted its log past server1's next entry, so
  it sent snapshots, which failed while server1 was stopped. The class now
  declares `RAFT_SNAPSHOT_TRANSFER_INTERRUPTED`.
- `DockerDurableRestartTest` logged Netty's `Stream Error` warning with
  `Http2Exception$StreamException: Stream closed before write could take place`.
  The leader-crash test kills the leader straight after a write, and a follower
  was answering its AppendEntries when it died. A definition can now name the
  exception an event carries (`DockerLogCaptureTest`, red first), and the new
  `GRPC_ANSWER_TO_KILLED_PEER` accepts this warning only with that exception.
- `DockerStartCommandsTest`: the five-server start brought server4 up 23 s
  before Docker Desktop started server3's container, so server3's name did not
  resolve meanwhile. The class now declares `RAFT_PEER_NAME_UNRESOLVED`. Its
  cleanup, four Docker commands, ran past JUnit's 15 s default for a lifecycle
  method and now has 5 minutes. It left nothing running.

In the same run, a call from server3 to a server1 that the test had taken down ended
with `DEADLINE_EXCEEDED` after 10 s on an open connection, which before would have
waited forever without a log line.

The second run passed default 1,231 with every coverage gate met, end-to-end 10
and Docker 41 (26 min 43 s), with no failures, errors or skips. Its 233 retained
logs hold 886 ERROR lines, all flagged, 580 stack traces, none under an unflagged
header, and no JVM crash file. Only `DockerDurableRestartTest`, which kills and
stops servers, had a call end at its deadline. None of the three newly declared
races recurred, so `DockerLogCaptureTest` is the evidence for their definitions.

Two runs then verified the Consul alignment. The first, on `06a5210` with the
keepalive change in the working tree (later `ee11971`), passed default 1,232 and
end-to-end 10 and failed `DockerDurableRestartTest` on one undeclared error:
`Failed to send InstallSnapshot chunk 1/1 to server2: DEADLINE_EXCEEDED`. Its
partition test cuts server2's network, and the new leader's snapshot to it waited
for a connection until its deadline; the retry after the network was restored
took 0.45 s. Before the deadlines that call would have waited until the partition
healed. The class now declares the new `RAFT_SNAPSHOT_CHUNK_UNANSWERED`, which
accepts only that message (`DockerLogCaptureTest`, red first); the stopped-peer
definition does not excuse it.

The second, on `ee11971` with that declaration in the working tree (later
`bc19492`), passed
default 1,233 with every coverage gate met, end-to-end 10 and Docker 43, the two
`GrpcRaftKeepaliveTest` tests among them (28 min 24 s), with no failures, errors
or skips. Its 232 retained logs hold 833 ERROR lines, all flagged, 566 stack
traces, none under an unflagged header, no JVM crash file, and no
`too_many_pings`.

## Builds 56 to 58, 2026-10-30 and 2026-10-31

Build 56 ran on `c33a2f2`, the first Jenkins build since build 55 on 2026-10-26.
It is the first on Jenkins of the client list's Phases 1 to 3, the transport's
deadlines and keepalive, and the client's anti-entropy. Jenkins answered from the
`mraysmit` workstation, where the `markr` workstation could not reach it.

It passed in 24 min 33 s: default 1,315 with every coverage gate met, end-to-end
11, and Docker and `slow` 43, 1,369 in all, with no failures, errors or skips.

The audit read the Jenkins console, 190 archived logs, 150 of them Docker archives
from 16 classes, and the test reports. Many events appear in more than one of them.
They hold 1,386 ERROR lines, all flagged, and 1,000 stack traces, none under an
unflagged header. There is no `too_many_pings` and no JVM crash file.
`DockerDeploymentContractTest` has no archive because it only reads files.

Build 57 ran on `a2459ee`, with the client list's Phase 4. It passed in 24 min 14 s:
default 1,375 with every coverage gate met, end-to-end 11, and Docker and `slow` 43,
1,429 in all, with no failures, errors or skips. The same audit found 1,478 ERROR
lines, all flagged, and 1,038 stack traces, none under an unflagged header, no
`too_many_pings` and no JVM crash file. The new code's only warnings are from tests
that cause them on purpose: a registration that cannot be kept, and a kept check
whose service is gone. They carry no stack trace.

Build 58 ran on `1a60239`, with the client list's Phase 5. It passed in 23 min 58 s:
default 1,408 with every coverage gate met, end-to-end 11, and Docker and `slow` 43,
1,462 in all, with no failures, errors or skips. The same audit found 1,286 ERROR
lines, all flagged, and 975 stack traces, none under an unflagged header, no
`too_many_pings` and no JVM crash file. The count of ERROR lines moves from build to
build with timing. Against build 57 the archived logs hold fewer `RAFT_PEER_UNREACHABLE`
(315 from 376), `RAFT_PEER_NAME_UNRESOLVED` (110 from 173) and
`RAFT_SNAPSHOT_TRANSFER_INTERRUPTED` (44 from 62), which account for most of the drop.

## Job configuration

The job loads its pipeline from the repository's default branch using the following SCM configuration.

- Type: Pipeline.
- Definition: Pipeline script from SCM.
- SCM: Git, repository `https://github.com/mraysmit/qraft.git`.
- Branch: `*/main`.
- Script path: `Jenkinsfile`.
- Lightweight checkout: enabled.

The pipeline checks out the same SCM revision that supplied its Jenkinsfile. It needs an online Linux
x64 node labelled `linux`, Bash, Git, curl, Maven 3.9 or newer, and access to a running Docker engine
with Docker Compose. The Jenkins controller's built-in node carries both its existing `peegeeq-linux`
label and the `linux` label used by Qraft. No Jenkins plugin installation is needed.

The first build downloads SapMachine JDK 27 from its official GitHub release and verifies its pinned
SHA-256 checksum. The JDK is cached in `<workspace>@tools/sapmachine-jdk-27`; Maven dependencies are
cached in `<workspace>@repository`. These directories survive fresh source checkouts. The pipeline
sets its own Java environment without changing other jobs' Java installations.

## Suites and results

1. **Default tests and coverage gates:** `mvn -B --fail-at-end -Dstyle.color=never clean install`.
   This also builds `target/qraft.jar`, the executable jar the Docker tests need.
2. **End-to-end tests:** `mvn -B -Dstyle.color=never test -Dgroups=e2e -Dtest.excludedGroups=`.
3. **Docker cluster tests:** `mvn -B -Dstyle.color=never test '-Dgroups=docker|slow' -Dtest.excludedGroups=`.
   Including `slow` also covers that group if tests are tagged with it later.

The job has two parameters, both for PIT mutation testing, which is on ice since 2026-10-11 at the
owner's decision: leave them at their defaults. `MUTATION_TESTING` is `off` by default. Set to `dry-run` or `run`, the job
runs PIT mutation testing instead of the three suites
([TESTING.md](TESTING.md#mutation-testing-with-pit)), on one batch of at most ten mutants, and archives
PIT's report under `reports/pit/`. `MUTATION_BATCH` names the batch, `01` to `05`: the POM profile
`pit-batch-<number>`. `dry-run` collects coverage and generates the batch's mutants without running
tests against them. A build with `off` runs the suites as before.

The build's limit is one hour. The PIT stage has its own limit of 20 minutes: a batch that runs longer
has gone wrong and is stopped.

Every Maven command uses the job's Maven repository cache, streams combined output to the Jenkins
console and a timestamped file under `logs/`, and preserves Maven's exit status through `tee`.
Builds and suites run sequentially. A failed default build stops the dependent suites; an end-to-end
failure still allows Docker tests to run.

The Docker stage's tests create and remove Docker networks, which changes the network interfaces of
the host. A browser on the host cancels a page load when that happens. On 2026-10-10, builds 6 and 11
ran beside PeeGeeQ builds 18 and 19, and 7 PeeGeeQ browser test attempts failed on a blank page within
seconds of this stage's network changes. For some hours that day this stage held a Jenkins lock shared
with the PeeGeeQ browser tests (builds 12 to 17). The lock is removed: the PeeGeeQ job now runs its
browser tests in a container, which has its own network interfaces, and build 17 ran beside PeeGeeQ
build 28 with no lost page load. A job that runs a browser directly on this node is still affected.

JUnit results appear under **Test Result**. Build artifacts contain the logs, complete Surefire reports
under `reports/default`, `reports/e2e`, and `reports/docker`, and the default build's JaCoCo reports under
`coverage/`. JVM fatal-error and replay logs are also retained. Reports are copied before a later suite
can overwrite them. Jenkins retains 20 builds and artifacts from the latest 10 builds.

## Linux container test fixtures

Qraft containers run as UID 1001. Java temporary files on Linux initially allow only their owner to read
them, so a configuration created by the Jenkins user cannot be read by a bind-mounted container.
`SharedDockerClusterFixture` grants read permission to the image's user for its non-secret agent and storage-lock
test configurations on POSIX filesystems. The image continues to run with its normal user, and the mounts
remain read-only. The existing Docker topology and storage-lock tests exercise these configurations.

## Phase 7 local verification, 2026-10-13

The executor ownership, test-helper removal and fenced-node diagnostics changes
are committed as `7a9108f`, `d876349` and `f737d6b`. Their final verification was
local: 960 default tests with all coverage gates and 42 runtime tests passed,
followed by the retained-log audit. These results are separate from Jenkins
build 26 below, which verified the earlier future-wrapper removal at `9851987`.
This document records no newer Jenkins run for the later commits; see
[TESTING.md](TESTING.md#phase-7-verification-record-2026-10-13) for the local
captures and [the task plan](archive/task-list-single-pom-and-quorus-removal-2026-10-21.md#phase-7-replace-the-vertx-shaped-async-layer)
for the detailed evidence. The pipeline commands and archival policy above
continue to apply to these changes.

## Current server setup, 2026-10-04

The memory fault recorded in the historical sections below is resolved, and the `Qraft` job was
recreated on the current Jenkins server as a Pipeline from SCM using `main` and
the root `Jenkinsfile`. The built-in node retains its `peegeeq-linux` label and also has the `linux`
label required by this pipeline. The job is manually triggered, prevents concurrent runs, and retains
20 builds plus artifacts from the latest 10 builds.

[Build 1](http://192.168.137.32:8080/job/Qraft/1/) verified checkout, JDK 27 provisioning, Maven,
Docker, report publication, and artifact archival. Its 803 default tests and 7 end-to-end tests passed.
The first Docker class exceeded its 15-second cluster startup deadline while Testcontainers downloaded
the `alpine/socat` helper image; the remaining Docker classes passed.

[Build 2](http://192.168.137.32:8080/job/Qraft/2/) confirmed the cached-startup case: both
`DockerRunningPartitionTest` tests passed. It published 833 JUnit results and 682 artifacts. One Docker
recovery case remained red:
`DockerAgentRecoveryTest.aKilledFollowerInstallsTheLeadersSnapshotAndThenHoldsTheLeadersHealthState`
timed out after 90 seconds while waiting for the restarted follower to install and expose the leader's
snapshot state. This is a test/product integration result rather than a Jenkins configuration failure.
Finding its cause is a task in Phase 6 of
[the single-POM task list](archive/task-list-single-pom-and-quorus-removal-2026-10-21.md).
It was analysed on 2026-10-10. This build archived no container logs, so its cause cannot be proven
from it. Build 8 failed the same way with the logs kept: the test had killed the follower while it wrote
its first snapshot, and a server then refused to start on an unpublished first snapshot, by design. An earlier
explanation, a deadline hidden in the client profile, was not the cause. That limit was real, and the
test keeps the client profile that removes it, `client-long-ttl.json`. The task list has the reasoning.

[Build 4](http://192.168.137.32:8080/job/Qraft/4/), of 2026-10-10 on commit `ce5890d`, is the first
complete pass of the pipeline on this server. It took 7 minutes 18 seconds.
- Default build: 892 tests, every coverage gate met. End-to-end: 10. Docker and slow: 25.
- Jenkins published 927 results, none failed or skipped, and archived 801 files.
- The 101 archived log files hold no unflagged error.
- The recovery test that timed out in build 2 passed. Its cause is still not known, so its task in
  Phase 6 stays open: one pass does not explain an intermittent failure.

[Build 5](http://192.168.137.32:8080/job/Qraft/5/), of the same day on commit `d4e1af8`, passed as
well: 898 default tests, 10 end-to-end, and 25 Docker and slow, 933 results in all, with no unflagged
error in its archived logs.

[Build 6](http://192.168.137.32:8080/job/Qraft/6/), of the same day on commit `d0275a7`, verified
Phase 6 of the single-POM task list. It took 8 minutes 28 seconds, with a PeeGeeQ build running beside it.
- Default build: 899 tests, every coverage gate met. End-to-end: 10. Docker and slow: 25.
- Jenkins published 934 results, none failed or skipped, and archived 817 files.
- The console and the 104 archived log files hold 691 flagged ERROR events, counting copies, and one
  flagged uncaught exception. No ERROR event and no uncaught exception is without a flag.
- Ten WARN events in the container logs carry a stack trace and no flag: gRPC reporting that it could
  not resolve a peer's name. Nine are in `DockerDurableRestartTest`, one in `DockerClientTopologyTest`.
  Builds 4 and 5 hold 15 and 10 of the same kind, which their records above do not mention: those audits
  looked for ERROR events and uncaught exceptions only. These are unflagged errors under the logging
  policy. Each comes from a server looking up a peer that its test has killed or disconnected. The
  remedy, a definition for the event and a Docker audit that checks stack traces below ERROR, is
  recorded in Phase 6 of the task list.
- The recovery test that timed out in build 2 passed in 12.1 seconds with its new client profile.

[Build 7](http://192.168.137.32:8080/job/Qraft/7/), of the same day on commit `cbb93f4`, failed, as
intended: it is the red run of the fix for those warnings. The commit holds the new definition and seven
new tests of the Docker log audit, without the change to the audit. `DockerLogCaptureTest` ran 27 tests
with the 5 predicted failures. The default suite ran 906 tests with those 5 failures, and Jenkins skipped
the end-to-end and Docker stages. It took 1 minute 23 seconds.

[Build 8](http://192.168.137.32:8080/job/Qraft/8/), of the same day on commit `362b507`, is the green
run of that fix, and failed for another reason. It took 9 minutes 55 seconds.
- Default build: 906 tests passed, the 27 of `DockerLogCaptureTest` among them. End-to-end: 10 passed.
- The container logs hold 17 gRPC name-resolution warnings, every one flagged, none without a flag.
- Docker stage: `DockerClientRecoveryTest.aKilledFollowerInstallsTheLeadersSnapshotAndThenHoldsTheLeadersHealthState`
  timed out after 90 seconds, as in build 2, and its class failed its log audit on two undeclared
  errors. The other 24 tests passed. Jenkins published 942 results, 2 of them failed.
- The follower's log gives the cause. The test killed it while it wrote its first snapshot. Started
  again, it logged "Refusing startup because unpublished first snapshot /app/data/snapshot.dat.tmp has
  no published /app/data/snapshot.dat" and ended. `docs/RAFT_STORAGE_OPERATIONS.md` documents that
  refusal, so the defect is the test's: it must not kill a server before its first snapshot is published.
  The fix is recorded in Phase 6 of the task list, and [TEST-RESULTS.md](TEST-RESULTS.md) describes the
  window and the tests of it.

[Build 9](http://192.168.137.32:8080/job/Qraft/9/), of the same day on commit `122d739`, passed with
that fix. It took 7 minutes 45 seconds.
- Default build: 906 tests, every coverage gate met. End-to-end: 10. Docker and slow: 25.
- Jenkins published 941 results, none failed or skipped, and archived 816 files.
- The console and the 103 archived log files hold 710 flagged ERROR events, counting copies, and one
  flagged uncaught exception. No ERROR event, no uncaught exception, and no stack trace is without a
  flag. The 5 gRPC name-resolution warnings of this run are all flagged.
- The recovery test passed in 13.0 seconds, and no server refused to start. One pass does not show
  that a race is gone; the order the tests now keep does: none kills a server before its first snapshot
  is published.

[Build 10](http://192.168.137.32:8080/job/Qraft/10/), of the same day on commit `aa0d876`, passed. It is
the first run on Jenkins of the seven tests that kill a server at each point of its first snapshot
([TEST-RESULTS.md](TEST-RESULTS.md)). It took 8 minutes 29 seconds.
- Default build: 913 tests, every coverage gate met. End-to-end: 10. Docker and slow: 25.
- Jenkins published 948 results, none failed or skipped, and archived 817 files.
- The console and the 104 archived log files hold 610 flagged ERROR events, counting copies, and one
  flagged uncaught exception. No ERROR event, no uncaught exception, and no stack trace is without a flag.

[Build 11](http://192.168.137.32:8080/job/Qraft/11/), of the same day on commit `f2119d3`, passed. It is
the first run on Jenkins of the change to recovery: an interrupted first snapshot is set aside and no
longer stops a server, and a log compacted further than the published snapshot reaches is refused
([TEST-RESULTS.md](TEST-RESULTS.md)). It took 9 minutes 1 second.
- Default build: 921 tests, every coverage gate met. End-to-end: 10. Docker and slow: 25.
- Jenkins published 956 results, none failed or skipped, and archived 827 files.
- The console and the 113 archived log files hold 887 flagged ERROR events, counting copies, and one
  flagged uncaught exception. No ERROR event, no uncaught exception, and no stack trace is without a flag.
- No container met either new path: none set a snapshot aside and none refused to recover. The Docker
  tests wait for first snapshots before they kill a server, so that is as intended. Both paths ran in the
  default build's tests, which place the kill exactly.

Builds 12, 13, 16, and 17 belong to the lock described under "Suites and results" and are not recorded
here.

[Build 14](http://192.168.137.32:8080/job/Qraft/14/), of the same day on commit `0f6c497`, failed in
its Docker stage. It is the first run of `DockerStartCommandsTest`, which runs every start command of
`docker/` with the shell scripts, and six of its seven tests failed.
- Default build: 925 tests, every coverage gate met. End-to-end: 10. Docker and slow: 26 passed and 6
  failed. Jenkins published 967 results.
- Five tests failed because `start.sh` and `start-quick.sh` called `build-runtime.sh` as a program, and
  the file is not executable in the repository: "Permission denied". The scripts could not start
  anything on Linux. They now run it with `sh`.
- One test failed on an error line that Tempo logs once when its first span arrives, before it recovers.
  The test now accepts exactly that line, and only with the recovery line after it.
- Outside the new class, the 121 archived log files hold no ERROR event, uncaught exception, or stack
  trace without a flag.

[Build 15](http://192.168.137.32:8080/job/Qraft/15/), of the same day on commit `da87cb3`, passed with
both fixes. It took 10 minutes 14 seconds.
- Default build: 925 tests, every coverage gate met. End-to-end: 10. Docker and slow: 32, the 7 of
  `DockerStartCommandsTest` among them.
- Jenkins published 967 results, none failed or skipped.
- The 140 archived log files hold 381 flagged ERROR events, counting copies. No ERROR event and no
  uncaught exception is without a flag. The observability containers logged the one Tempo line and no
  other error line.

[Build 18](http://192.168.137.32:8080/job/Qraft/18/), of 2026-10-11 on commit `fac3696`, failed in its
default stage. It is the first build against `raftlog-core` 1.5.0, which it downloaded and compiled
against. 924 tests passed and 1 failed, and Jenkins skipped the two later stages.
- `GrpcRaftServerTest#requestVoteWithAnAlreadyExpiredDeadlineFailsWithDeadlineExceeded` logged Netty's
  warning "Failed to initialize a channel" with a stack trace and no flag. The test's one call fails
  before it is sent but still starts a connection, and teardown stopped the server while the server
  was accepting it. The test now connects before it calls.
- The 25 archived log files hold 279 flagged ERROR events and no ERROR event or uncaught exception
  without a flag.

[Build 19](http://192.168.137.32:8080/job/Qraft/19/), of the same day on commit `f9b3403`, passed. It
holds the move to `raftlog-core` 1.5.0, that fix, and two tests that place a connection exactly in and
after the instant a gRPC server stops. It took 10 minutes 34 seconds.
- Default build: 927 tests, every coverage gate met. End-to-end: 10. Docker and slow: 32.
- Jenkins published 969 results, none failed or skipped.
- The 141 archived log files hold 377 flagged ERROR events, counting copies. No ERROR event and no
  uncaught exception is without a flag. The gRPC warning that the new test causes is flagged.

[Build 20](http://192.168.137.32:8080/job/Qraft/20/), of the same day on commit `3579736`, was started
by the owner and passed: 969 results, none failed.

[Build 21](http://192.168.137.32:8080/job/Qraft/21/), of the same day on commit `e3cf808`, passed. It is
the ordinary build of the commit that added the PIT profile to the POM and the mutation stage to the
pipeline: 927 default tests, 10 end-to-end, 32 Docker and slow, 969 results, none failed. Its 141
archived log files hold 539 flagged ERROR events and no ERROR event or uncaught exception without a
flag.

Builds 22 to 24 were PIT runs, with the three suites skipped. PIT has been on ice since that evening
([TESTING.md](TESTING.md#mutation-testing-with-pit)).
- [Build 22](http://192.168.137.32:8080/job/Qraft/22/), a dry run on `e3cf808`, failed in 22 seconds on
  PIT's configuration: `withHistory` needs a history plugin that the open-source release does not
  include. It was removed.
- [Build 23](http://192.168.137.32:8080/job/Qraft/23/), a dry run on `03bea0f`, passed in 75 seconds:
  1,187 mutants in three classes, and no ERROR event without a flag in its 25 log files.
- [Build 24](http://192.168.137.32:8080/job/Qraft/24/), the full analysis of those 1,187 mutants, was
  aborted after two hours with no report. PIT had ended 160 mutants that made a test hang, at a steady
  one every 44 seconds, and the whole run would have taken days.

[Build 25](http://192.168.137.32:8080/job/Qraft/25/), of 2026-10-12 on commit `a37a684`, passed in ten
minutes. It verified the first slice of the replacement of the wrapper future types, in
`RaftStorageFactory`: 933 default tests, 10 end-to-end, 32 Docker and slow, 975 results, none failed or
skipped, and every coverage check met. The six new results are `CompletionsTest` and
`RaftStorageFactoryTest`. Its 139 archived log files hold 531 flagged ERROR events, counting copies, and
no ERROR event or uncaught exception without a flag.

[Build 26](http://192.168.137.32:8080/job/Qraft/26/), of the same day on commit `9851987`, passed in ten
minutes. It verified the rest of that replacement: the wrapper types `Future`, `Promise`, and
`AsyncResult` are deleted, and production and test code use `CompletableFuture`. 931 default tests, 10
end-to-end, 32 Docker and slow, 973 results, none failed or skipped, and every coverage check met. Its
139 archived log files hold 378 flagged ERROR events, counting copies, and no ERROR event or uncaught
exception without a flag.

No build ran between 2026-10-04 and 2026-10-10. In that time the `agent` directive of the
`Jenkinsfile` had been renamed by mistake, which Jenkins would have refused; it was restored in
`ce5890d` before build 4.

An assistant starts this job only when the user asks for that build (see
[TESTING.md](TESTING.md#run-builds-in-a-visible-terminal-through-tee-object)). It reads the token from
`.env/jenkins-api-token.txt` and never writes its value anywhere.

## Historical: the memory fault, 2026-10-03

The two sections below describe the Jenkins server as it was on 2026-10-03, when its memory was
faulty. They are kept as a record. The `Qraft` job was recreated on 2026-10-04, so the build links in them now
open the current job's builds of the same number, not the builds described, and the
`Qraft-Diagnostics` links may no longer resolve.

### Initial verification, 2026-10-03

[Build 1](http://192.168.137.32:8080/job/Qraft/1/) ran 842 tests: 812 default tests and all coverage gates
passed, all 7 end-to-end tests passed, and the Docker suite ran 23 tests with 11 failures/errors.
The second-agent and storage-lock fixtures exposed the temporary-file permissions issue above.
Other Docker failures included native Java `SIGSEGV` crashes; container logs and JVM fatal-error reports
were retained under `logs/docker-diagnostics` in that build's artifacts.

[Build 2](http://192.168.137.32:8080/job/Qraft/2/) applied the fixture fix but the host Maven JVM crashed
with `SIGSEGV` and exit code 134 before completing the reactor, so the fix remains unverified by the
full suite. The host Docker CLI also crashed with `SIGSEGV` during an isolated diagnostic check.
These observations require server-level diagnosis; neither increasing test timeouts nor rerunning
unchanged tests establishes compatibility. No recurring build trigger is enabled.

### Memory corruption investigation, 2026-10-03

[Build 3](http://192.168.137.32:8080/job/Qraft/3/) verified checkout of the published CI revision
`50e28d75c634f79d5c23b64d0fb2addfbe9aced2`. It stopped after 314 reported passing tests when the
controller test JVM received `SIGSEGV` in `PcDescContainer::find_pc_desc_internal`. Its fatal-error
report is archived as `qraft-controller/hs_err_pid42492.log`.

The Linux server is a VMware VM named `ubu24-cicd`, reporting four virtual CPUs with an Intel
Core i7-10710U CPU model and 16 GiB RAM. The investigation confirmed memory corruption visible
inside this environment; it has not identified whether the faulty component is host RAM, CPU,
the hypervisor, or another part of the host memory path.

The manually triggered [Qraft-Diagnostics job](http://192.168.137.32:8080/job/Qraft-Diagnostics/)
retains the diagnostic sources, logs, and results:

- [Diagnostic build 1](http://192.168.137.32:8080/job/Qraft-Diagnostics/1/) ran the affected
  `HttpApiServerOperatorTest` twice per JVM mode, with JaCoCo disabled. Baseline runs returned
  134 and 0; disabling compact object headers returned 0 and 0; disabling the JIT with `-Xint`
  returned 0 and 134. These short runs do not establish a safe JVM workaround. The standalone
  Java probes in this first diagnostic build did not execute because Java 21's installation
  contained no `javac`; build 2 corrected compilation using Java 25 with `--release 21`.
- [Diagnostic build 2](http://192.168.137.32:8080/job/Qraft-Diagnostics/2/) found mismatches in
  a 256 MiB native C write/read check compiled both with `gcc -O2` and with `gcc -O0`. For example,
  the optimized check wrote `ed9d8b3def82b11a` and read `ed9d8b3d6f82b11a`; the unoptimized check
  wrote `e6f56c85b11a8c32` and read `e6f56c85911a8c32`. The probe uses volatile memory and unsigned
  arithmetic, with no Java, Docker, Qraft, or instrumentation. Three short standalone probes each
  on Java 21, 25, and 27 passed; intermittent corruption cannot be ruled out by these passes.
- [Diagnostic build 3](http://192.168.137.32:8080/job/Qraft-Diagnostics/3/) independently verified
  corruption with Ubuntu's `memtester` 4.6.0. The full requested **256 MiB allocation was locked**;
  it reported **6,537 failure messages** in address-pattern and data comparisons and exited **6**.
  The complete evidence is archived as `logs/memtester-locked.log`. Build 2's earlier memtester
  run had reduced its allocation to the Jenkins user's 8 MiB lock limit and passed; it did not
  test the full requested memory.

The diagnostic utility was downloaded through Ubuntu's configured APT repository and extracted
into the diagnostic workspace, without installing an OS package. The full locked check ran in a
temporary container with read-only mounts of the utility and host C libraries. Its container was
removed after completion. No global Jenkins, Java, Docker, or VM settings were changed.

At the time, further diagnosis needed access to the machine hosting the VM, and the fixture fix
and the full pipeline were unverified. The fault has since been resolved and the job recreated;
see "Current server setup" above for the runs on the current server.
