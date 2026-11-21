# Running and Auditing the Tests

**Last updated:** 2026-11-21

This document says how to run Qraft's tests and how to audit what they log. The rules that tests must follow are in
[PROJECT_STANDARDS.md, section 4](PROJECT_STANDARDS.md#4-testing); the rules for an assistant working in this
repository are in [AGENTS.md](../AGENTS.md). The Jenkins job itself is described in [JENKINS.md](JENKINS.md).

## Prerequisites

- **JDK 27.** The enforcer refuses older JDKs. Point `JAVA_HOME`, and your IDE's test runtime, at it.
- **Maven.**
- **Docker**, for the Docker suite only.
- **PowerShell 7**, for the UTF-8 log capture below. Windows PowerShell 5.1 writes UTF-16 logs.
- Optional: the **Maven daemon**, `mvnd`, which takes the same arguments as `mvn` and keeps Maven warm between runs.
- **Network access to nodejs.org and the npm registry**, for the administrative interface in `src/main/ui`. No Node
  installation is needed: in `generate-resources`, `frontend-maven-plugin` installs the Node that `pom.xml` names into
  `target/node`, runs `npm ci` on the committed lockfile, and builds into `target/generated-resources/ui`. In `test`,
  Vitest runs the interface's tests after the Java tests. `-DskipTests` skips both; `-DskipVitest=true` skips Vitest
  alone. `EmbeddedUiResourcesTest` reads what that build embeds, so it passes only after a Maven build.

In PowerShell, quote every `-D` argument, for example `"-Dtest=RaftNodeTest"`; otherwise PowerShell splits the
argument at the dot.

## The three suites

| Suite | Selection | What it covers | Time on Jenkins |
|---|---|---|---|
| **Default** | everything not tagged `docker`, `slow` or `e2e` | Unit and in-process integration tests: Raft on manual timers, gRPC on local ports, HTTP, storage, client, the runtime's start-up; Vitest; the JaCoCo gates | about 3 min |
| **End-to-end** | `@Tag("e2e")` | Real server and client processes that wait out real TTLs and real leader changes | about 1 to 3 min |
| **Docker and slow** | `@Tag("docker")` or `@Tag("slow")` | Server clusters in containers: restarts, partitions, membership changes, clients, every start command of `docker/`, both observability stacks; and `GrpcRaftKeepaliveTest`, the one class tagged `slow` | 26 to 34 min |

A whole Jenkins build takes about 30 minutes. `pom.xml` sets `test.excludedGroups` to `docker,slow,e2e`, so the
default build runs none of the tagged tests. A tagged suite needs both `-Dgroups`, which selects it, and an empty
`-Dtest.excludedGroups=`, which lifts the default exclusion.

## Run every suite on Jenkins

The suites normally run on the [Qraft job](http://192.168.137.32:8080/job/Qraft/), which builds the pushed `main`
branch, not a working tree. The job loads the root [`Jenkinsfile`](../Jenkinsfile) and runs these commands, in this
order, after checking its toolchain:

```bash
mvn -B --fail-at-end -Dstyle.color=never -Dmaven.repo.local="$WORKSPACE@repository" clean install
mvn -B -Dstyle.color=never -Dmaven.repo.local="$WORKSPACE@repository" test \
    -Dgroups=e2e -Dtest.excludedGroups= -DskipVitest=true
mvn -B -Dstyle.color=never -Dmaven.repo.local="$WORKSPACE@repository" test \
    '-Dgroups=docker|slow' -Dtest.excludedGroups= -DskipVitest=true
```

The first runs the default suite, Vitest and the coverage gates, and packages `target/qraft.jar`, from which the
Docker fixtures build their image. Vitest runs in that stage only. Each command's output goes through `tee` to the
console and to `logs/qraft-default-`, `qraft-e2e-` or `qraft-docker-<UTC timestamp>-<build>.log`, and the step exits
with Maven's status. A failed default suite stops the build; a failed end-to-end suite still lets the Docker stage
run. Stages, time limits, archived artifacts, and how to start a build and download its results through the API are in
[JENKINS.md](JENKINS.md). A run is accepted only after its logs are audited: see [Auditing a run](#auditing-a-run).

### Jenkins access

- **URL:** <http://192.168.137.32:8080/>
- **User name:** `mraysmit`
- **API token:** `.env/jenkins-api-token.txt`, relative to the repository root. `.env/` is git-ignored.

API requests use HTTP Basic authentication, with the user name and the API token as the password. Read the token from
the file inside the command; never print it or write its value into a document or a log.

## Run builds in a visible terminal, through `Tee-Object`

Local runs are for small checks while you work, and for the suites when Jenkins cannot be reached. Run them in a
console you can watch, and pipe the output through `Tee-Object` into the git-ignored `logs/` directory, so each run
leaves a timestamped UTF-8 log. The default suite, as Jenkins runs it:

```powershell
New-Item -ItemType Directory -Force .\logs | Out-Null
mvn -B --fail-at-end "-Dstyle.color=never" clean install 2>&1 | Tee-Object -FilePath ".\logs\qraft-default-$(Get-Date -Format 'yyyy-MM-dd_HH-mm-ss-fff').log"
$qraftBuildExitCode = $LASTEXITCODE
if ($qraftBuildExitCode -ne 0) { throw "Maven failed with exit code $qraftBuildExitCode" }
```

Check `$LASTEXITCODE` straight after every pipeline, with the last two lines above: a successful `Tee-Object` does
not prove that Maven passed. The tagged suites take the same capture and check:

```powershell
mvn -B "-Dstyle.color=never" test "-Dgroups=e2e" "-Dtest.excludedGroups=" "-DskipVitest=true" 2>&1 | Tee-Object -FilePath ".\logs\qraft-e2e-$(Get-Date -Format 'yyyy-MM-dd_HH-mm-ss-fff').log"
mvn -B "-Dstyle.color=never" test "-Dgroups=docker,slow" "-Dtest.excludedGroups=" "-DskipVitest=true" 2>&1 | Tee-Object -FilePath ".\logs\qraft-docker-$(Get-Date -Format 'yyyy-MM-dd_HH-mm-ss-fff').log"
```

On Windows write `docker,slow`: `mvn.cmd` runs through `cmd.exe`, which reads `|` as a pipe. Both forms select the
same tests.

**Run from a copy of the tree outside the IDE's workspace.** The IDE can rewrite `target/classes` while Maven runs,
which loses compiled classes mid-build, and the workspace's `src/main/ui/node_modules` can be held open, which makes
`npm ci` fail with `EPERM`. A clone at a short path, reset to the committed tree with the uncommitted files copied over
it, avoids both; `protobuf-maven-plugin` fails under a long path on Windows. Copying sources over a copy after its
build makes them newer than its jar, and the Docker suite then refuses to run until `mvn install` packages it again.

Keep Maven the only writer of `target/` during a run, and do not start a second build in the same tree while the
Docker suite runs: it overwrites the classes and the jar under test. A laptop must stay on mains power with its lid
open, or the machine suspends and a Docker test times out.

## While you work: focused runs

One class, or several:

```powershell
mvn -B "-Dstyle.color=never" test "-Dtest=RaftNodeMembershipTest" "-DskipVitest=true" 2>&1 | Tee-Object -FilePath ".\logs\qraft-tests-$(Get-Date -Format 'yyyy-MM-dd_HH-mm-ss-fff').log"
mvn -B "-Dstyle.color=never" test "-Dtest=RaftNodeMembershipTest,MembershipServiceTest" "-DskipVitest=true" 2>&1 | Tee-Object -FilePath ".\logs\qraft-tests-$(Get-Date -Format 'yyyy-MM-dd_HH-mm-ss-fff').log"
```

- Qraft is one Maven module: there is no `-pl` and nothing to install first.
- `-Dtest` does not stop Vitest; `-DskipVitest=true` does. Leave it out when the change touches `src/main/ui`.
- `-Dtest` does not lift the tag exclusion. To run a tagged class, add `"-Dgroups=<tag>"` and `"-Dtest.excludedGroups="`.
  A pattern such as `GrpcRaft*Test` leaves out `GrpcRaftKeepaliveTest` unless `slow` is selected.
- Add `clean` after deleting or renaming classes, so that `target/` holds none of the old ones, and when JaCoCo warns
  that its execution data does not match the classes.

A change to concurrency or lifecycle behaviour is run repeatedly before it is complete
([PROJECT_STANDARDS.md, section 4.4](PROJECT_STANDARDS.md#44-deterministic-tests)): run the selection several times
in a row, each as a separate command, and keep every capture. A failed run is investigated; a later pass does not
erase it.

### Focused selections by area

| Area | `-Dtest=` |
|---|---|
| Membership settings and their wiring | `AppConfigRaftMembershipTest,RaftMembershipPolicyTest,QraftServerLifecycleTest` |
| Promotion, failed-voter cleanup, disruptive servers | `RaftNodePromotionTest,RaftNodeCleanupTest,RaftNodeDisruptionTest,RaftNodeMembershipTest,MembershipServiceTest` |
| Elections, quorum and Raft sequencing | `RaftNode*Test,RaftTransitionSequencer*Test` |
| State loop, blocking executor, shutdown | `BlockingTaskExecutorTest,RaftStateLoopTest,ShutdownCoordinatorTest,QraftServerLifecycleTest` |
| gRPC transport and bootstrap | `GrpcRaft*Test,ClusterBootstrapTest` |
| Fencing diagnostics | `RaftTransitionSequencer*Test,RaftNodeApplyFailureTest,RaftNodeConfigurationTest,RaftNodeMetadataSequencingTest,HttpApiServerTest` |
| Package boundaries and protocol names | `PackageDependencyTest,ProtocolPackageTest` |
| Logging configuration and the error audit itself | `LoggingConfigurationTest,IntentionalErrorConfigurationTest,IntentionalErrorsTest,LogbackConfigurationAuditTest,DockerLogCaptureTest,SubprocessOutputAuditHelperTest` |
| The embedded interface | `EmbeddedUiResourcesTest,HttpApiServerAdminUiTest`, after a Maven build |

### Time limits

`src/test/resources/junit-platform.properties` gives every test 90 s and every lifecycle method 15 s. Vitest gives a
test 60 s (`src/main/ui/vite.config.ts`) and Testing Library's waits 15 s (`src/main/ui/src/setupTests.ts`). The
limits exist to report a hang, not to time a slow machine.

## Coverage: `mvn install`

`mvn test` does not check coverage. JaCoCo instruments the tests from the start of the build, its report is written in
`test` to `target/site/jacoco/index.html`, and its check runs in `verify`, so a change can pass `mvn test` and fail
`mvn install`. The threshold and the classes the check leaves out are in
[PROJECT_STANDARDS.md, section 4.6](PROJECT_STANDARDS.md#46-coverage).

`mvn install` also packages `target/qraft.jar`, the executable jar the Docker suite needs. To see which version of a
dependency the jar holds:

```bash
unzip -p target/qraft.jar META-INF/maven/io.github.mraysmit/raftlog-core/pom.properties
```

What a finished change needs beyond this is in
[PROJECT_STANDARDS.md, section 7](PROJECT_STANDARDS.md#7-verification-and-completion).

## The Docker suite

The Docker tests deploy themselves; nothing is deployed by hand first.

1. `SharedDockerClusterFixture` checks that `target/qraft.jar` exists and is newer than `pom.xml` and everything under
   `src/main`. Run `mvn install` first; `docker/build-runtime.ps1` is not needed after it.
2. `DockerImageExtensionHelper`, registered through `META-INF/services`, builds the `qraft-runtime:test` image once per
   run, before the first class tagged `docker`, with
   `docker compose -f src/test/resources/docker-compose-build-image.yml build`. It runs in a class callback, which no
   `@Timeout` bounds, so a slow build, such as one that pulls a base image the cache has lost, counts against no
   test's limit. A build that fails is never retried: every later class fails with that failure as its cause.
3. Each class starts its clusters from the compose files in `src/test/resources`, with the `docker/config/*-acceptance`
   configurations, and removes them when it finishes.

`DockerStartCommandsTest` runs every start command of `docker/` and of `start-observability`: the shell scripts on
Linux and the PowerShell scripts on Windows. It uses a Compose project of its own, `qraft-start-commands`, with
`PUBLISHED_PORT=0`, so it publishes on free host ports and removes only its own containers and volumes. It fails while
a cluster started by hand is running, because the container names are taken. Stop such a cluster first; the start
commands themselves are in [docker/README.md](../docker/README.md).

## Making runs faster

- Name only the classes you need with `-Dtest` while you work, and leave the suites to Jenkins.
- Use `mvnd` in place of `mvn` for repeated small runs.
- Surefire runs the test classes one after another in one forked JVM. `-DforkCount=2` would run two JVMs at once, but
  it is not used: `TestPortsHelper.freePort()` keeps ports unique only within one JVM, so two forks can hand out the same
  port.
- On Windows, Docker Desktop reserves runs of ports for one protocol
  (`netsh int ipv4 show excludedportrange protocol=tcp`, and `protocol=udp`); a bind inside one fails as
  `Address already in use`.

## Results and logs

| Where | What |
|---|---|
| `target/surefire-reports/` | One XML and one text file per class, with its time; a `*.dumpstream` file when a forked JVM corrupted its channel to Surefire |
| `target/site/jacoco/index.html` | The coverage report, written in `test` |
| `logs/qraft-maven-tests-<timestamp>.log` | The application log of a test JVM, from `src/test/resources/logback-test.xml`. Each JVM writes its own file, named for the second it started, so a helper JVM on the test classpath writes one too |
| `logs/docker/<TestClass>/` | One archive per container or helper process of a Docker class, audited and flagged at class teardown |
| `logs/phase3-runtime-process-<mode>-<id>/` | The console and file logs of the fresh server and client JVMs that `RuntimeLoggingTest` starts with the production Logback configuration |
| `logs/qraft-<suite>-<timestamp>.log` | A captured Maven output: locally through `Tee-Object`; on Jenkins `qraft-default`, `qraft-e2e` and `qraft-docker-<timestamp>-<build>.log` |

Nothing trims `logs/` on a workstation. To compare two captures, use `git diff --no-index <older> <newer>`;
timestamps, UUIDs, temporary paths, ports and election timing differ between runs, so normalise them, or compare the
Surefire XML where an exact comparison matters.

## Intentional error flags and log auditing

Every error a test causes on purpose carries a flag that names the failure and the test responsible. Every other error
is a defect to investigate and fix. A passing test and a zero exit code do not show that the logs are acceptable.

| Flag | Meaning | Attribution |
|---|---|---|
| `*** INTENTIONAL ERROR: <entry>, caused by <test> ***` | The test arranged a condition that makes production code report an error. | `TestClass#testMethod`; a Docker archive names the test class. |
| `*** INJECTED FAILURE: <entry>, injected by <test> ***` | The test supplied a failure through `InjectedFaultFixture`. | The running test that owns the logging scope. |

A flag keeps the event's level, message, fields and stack trace. It filters, suppresses and downgrades nothing. A flag
on an exception header covers the stack trace that follows it; frames and `Caused by:` lines do not repeat it.

### The machinery

All of it is in `dev.mars.qraft.testing.fault`, under `src/test/java`.

- **`IntentionalErrorFixture`** lists every error the suite causes on purpose, and nowhere else. An intentional-error
  entry names the exact logger, level and full message pattern and, where the message alone is too broad, the
  exception the event carries, as the first line of its trace reads. `GRPC_ANSWER_TO_KILLED_PEER`, for example,
  accepts Netty's stream-error warning only with `Http2Exception$StreamException: Stream closed before write could
  take place`.
- **`IntentionalErrorsHelper`** declares an expected error inside the current test's scope, before the test causes it:
  `expect(error)` needs at least one occurrence, `expect(error, times)` exactly that many, and
  `expect(RAFT_PEER_UNREACHABLE, peer)` accepts the failure only for the named peer. A missing occurrence, or a count
  outside its bounds, fails the audit. `expectPeerShutdownDuringCleanup(peer)` declares, just before teardown closes a
  peer, the peer-unreachable errors that closing it may cause; it allows zero, and those errors never count towards a
  test's functional expectations.
- **`IntentionalErrorExtensionHelper`** opens a scope around each test, its teardown included, and one around each
  class. JUnit registers it for every test through `META-INF/services`, with
  `junit.jupiter.extensions.autodetection.enabled=true` in `junit-platform.properties`.
- **`IntentionalErrorCheckHelper`** is a Logback appender on the root logger that writes nothing and records each
  undeclared ERROR, and each event that carries an undeclared exception at any level. The extension refuses a test
  runtime in which it is not attached to the root logger.
- **`IntentionalErrorLabelHelper`** is the `%intentional` conversion word of `logback-test.xml`, which writes the flag
  after the level.
- **`LogbackConfigurationAuditHelper`** rejects a Logback configuration that could drop an error: a filter, a turbo
  filter, an include, a discarding asynchronous appender, or a prudent file appender. `IntentionalErrorConfigurationTest`
  applies it to `logback-test.xml`, and `LoggingConfigurationTest` to the production `logback.xml`.

A test or class fails for an undeclared error or an unmet count even when its assertions pass. Errors logged outside
any test's scope are checked when the class closes.

### Declared intentional errors

A declared error, as the console shows it:

```text
12:00:00.000 [qraft-state-loop] ERROR *** INTENTIONAL ERROR: RAFT_PEER_UNREACHABLE, caused by GrpcRaftTlsTest#aServerWithACertificateFromAnotherCaTakesNoPart *** dev.mars.qraft.raft.RaftNode [node1] [FOLLOWER/0] - Failed to retrieve pre-vote from node2
io.grpc.StatusRuntimeException: UNAVAILABLE: io exception
Channel Pipeline: [SslHandler#0, ProtocolNegotiators$ClientTlsHandler#0, WriteBufferingAndExceptionHandler#0, DefaultChannelPipeline$TailContext#0]
	at io.grpc.Status.asRuntimeException(Status.java:532)
```

The exception's message is two lines long: `Channel Pipeline: ...` is its second line, not a new event.

### Injected failures

`InjectedFaultFixture` is an exception that names an injected-failure entry. The helpers recognise it in the logged
exception, its causes or its suppressed exceptions, with no declaration needed; `expect` can add an exact count. The
flag sits on the event's header, and the `InjectedFaultFixture` line below it belongs to that event:

```text
12:00:00.000 [main] ERROR *** INJECTED FAILURE: SHUTDOWN_HOOK_FAILURE, injected by TimeoutHandlingTests#synchronousCriticalHookFailureIsReportedAsynchronously *** d.m.qraft.server.lifecycle.ShutdownCoordinator - Critical shutdown hook 'node-stop' failed with InjectedFaultFixture
dev.mars.qraft.testing.fault.InjectedFaultFixture: node stop failed before returning
	at dev.mars.qraft.server.lifecycle.ShutdownCoordinatorTest$TimeoutHandlingTests.synchronousCriticalHookFailureIsReportedAsynchronously(ShutdownCoordinatorTest.java:318)
```

### Helper subprocesses

A helper JVM that returns opens its own scope with `IntentionalErrorsHelper.inSubprocess(owner, body)` and returns
through the audit before it calls `System.exit`. `DirectoryLockProcessFixture` takes the calling test's name and an
`owner` or `contender` role; only the contender declares exactly one `WAL_DIRECTORY_ALREADY_LOCKED`, and it exits with
code `73` once its audit passes. The parent test checks that exit code, the flagged error, and the owner's success, so
an audit failure cannot pass as the contender's expected failure.

A helper JVM that never returns cannot audit itself: a crash writer halts at its checkpoint, and some end-to-end tests
kill the runtime they started. Once it has ended, its parent test passes its whole console output to
`SubprocessOutputAuditHelper.requireNoErrors`, which fails the test on any ERROR line, Logback status error or uncaught
exception, quoting each with its trace. These JVMs declare no error, so a flag does not excuse one. The callers are the
crash-writer tests `RaftNodeRealStorageRecoveryTest`, `RaftNodeRealSnapshotRecoveryTest`,
`RaftNodeInstalledSnapshotRealRecoveryTest` and `OfflineRecoveryProcessTest`, and the end-to-end tests
`ClientLeaveEndToEndTest`, `CrashedClientExpiryEndToEndTest`, `WipedClientDirectoryEndToEndTest` and
`PackagedAdminUiEndToEndTest`.

### Docker archives and uncaught exceptions

A Docker class declares its expected errors with the annotation `@ExpectedDockerErrorsHelper({...})`. At class
teardown, `DockerLogExtensionHelper` has `DockerLogCaptureHelper` audit every captured container and helper output
against those declarations and write it to `logs/docker/<TestClass>/`, with each matching line flagged and its
original timestamp and trace kept. A recognised error is also replayed into the main test log with the class as its
attribution; the replay's timestamp and `[main]` thread are those of the audit, not of the event. The audit fails the
class on any of these:

1. **An undeclared or unparseable ERROR line.**
2. **An uncaught exception** (`Exception in thread ...`) that does not match a declared rethrow signature together
   with the same failure, logged earlier by the same container or process and declared by the same class. The
   lock-conflict signature of `SERVER_STORAGE_ALREADY_LOCKED` requires the `CompletionException` and
   `FileRaftStorage$StorageException` types, the same directory and the same message. Docker merges stdout and
   stderr, so frames after an uncaught exception belong to it, and the recognised rethrow is not replayed a second
   time.
3. **An event below ERROR followed by stack-trace lines**, unless a definition names it at its own level. The one such
   definition is `RAFT_PEER_NAME_UNRESOLVED`, gRPC's WARN for a name lookup of a killed or disconnected peer;
   `DockerLogCaptureTest` checks that every class declaring `RAFT_PEER_UNREACHABLE` declares it too.
4. **A command's error line.** A command run once from the image, such as `operator raft remove-peer`, writes a refusal
   as `Error <status>: <code>: <message>`, with no logger or level. Such a line is audited as an ERROR of
   `dev.mars.qraft.runtime.RaftOperatorCommand`; `RAFT_OPERATOR_TOKEN_REFUSED` and `RAFT_OPERATOR_CHANGE_IN_PROGRESS`
   are its definitions.
5. **A native log error** of the observability programs: `level=error` or `level=fatal`, a tab-delimited error level,
   or an `error=` or `err=` field at any level (`err=null`, which Loki writes when there is none, is not an error). Two
   definitions exist. `TEMPO_METRICS_WAL_STARTUP` is Tempo's `error tailing WAL` race at start-up, accepted once per
   source and only with a later `Done replaying WAL` for the same tenant, remote name and endpoint.
   `GRAFANA_SQLITE_LOCK_RETRY` is Grafana's INFO-level `database is locked` retry, which `DockerStartCommandsTest`
   declares. `DockerStartCommandsTest` also checks the stacks' live output before it stops them.

An archived lock conflict, from `logs/docker/DockerDurableRestartTest/lock-contender.log`:

```text
12:00:00.000 [wal-executor] ERROR *** INTENTIONAL ERROR: SERVER_STORAGE_ALREADY_LOCKED, caused by DockerDurableRestartTest *** dev.mars.qraft.server.QraftServerService - Failed to initialize Raft storage: Cannot acquire exclusive lock on WAL directory: /app/data. Another process may be using this storage.
Exception in thread "main" *** INTENTIONAL ERROR: SERVER_STORAGE_ALREADY_LOCKED, caused by DockerDurableRestartTest *** java.util.concurrent.CompletionException: dev.mars.raftlog.storage.FileRaftStorage$StorageException: Cannot acquire exclusive lock on WAL directory: /app/data. Another process may be using this storage.
	at java.base/java.util.concurrent.CompletableFuture.wrapInCompletionException(Unknown Source)
```

### A library's warning inside the test JVM

A definition can name a library's logger and a level below ERROR. `GRPC_CONNECTION_ACCEPTED_AS_SERVER_STOPS` is Netty's
WARN `Failed to initialize a channel`, with an `IllegalReferenceCountException`: a stopping gRPC server releases its
resources and then marks itself terminated, and a connection prepared between the two is closed with this warning.
One test of `GrpcRaftServerTest` places a connection there and declares it once; in any other test it is a teardown
defect ([PROJECT_STANDARDS.md, section 4.3](PROJECT_STANDARDS.md#43-test-isolation)).

### Auditing a run

No audit checker is committed. Whoever accepts a run reads its logs to these rules:

- **What to read:** the captured Maven output of every suite, every `logs/qraft-maven-tests-*.log` the run wrote, the
  helper-JVM folders, every archive under `logs/docker/`, and the Surefire reports. For a Jenkins build, download its
  artifacts first ([JENKINS.md](JENKINS.md)).
- **What counts:** every line holding the word `error` or `fatal`, in any case, and every stack trace, at any level.
  Each must carry a flag, or sit under an event whose header carries one.
- **Known lines under a flagged header:** the second line of gRPC's two-line exception message, `Channel Pipeline:
  ...`; and the `InjectedFaultFixture` line of an injected failure, whose flag is on the line before.
- **Crash files:** look for `hs_err_pid*`, `replay_pid*` and `*.dumpstream`. Any one is a defect.
- **Totals:** Surefire counts a class whose log audit fails at class close as one more test, beside its methods.

Check that each flag names the responsible test, and that its message and trace describe the failure that test caused.
An unflagged error is investigated and fixed. It is not dismissed as noise, filtered, lowered in severity, or covered
by a broader declaration. When evidence shows that a test causes it on purpose, give it its own definition and
declaration, then audit the run again.

## Mutation testing with PIT

PIT is shelved at the owner's decision: do not run, extend or propose it. Its configuration stays in `pom.xml`
(profiles `mutation-testing` and `pit-batch-01` to `pit-batch-05`) and in the Jenkinsfile's `MUTATION_TESTING`
parameter, which stays `off`.
