# Running the Tests

## Prerequisites

- **JDK 27.** The build refuses older JDKs. Point `JAVA_HOME`, and your IDE's test runtime, at it.
- **Maven 3.9 or newer.**
- **Docker**, for the Docker suite only.
- **PowerShell 7**, for the UTF-8 log-capture commands below.
- Optional: the **Maven daemon**, `mvnd`. It takes the same arguments as `mvn`, and keeps Maven warm between
  runs, which saves most of the start-up cost of each small run.

In PowerShell, quote every `-D` argument, for example `"-Dtest=RaftNodeTest"`. Otherwise PowerShell splits
the argument at the dot.

## Jenkins access

- **URL:** <http://192.168.137.32:8080/>
- **Username:** `mraysmit`
- **API token:** stored locally in `.env/jenkins-api-token.txt`, relative to the repository root.

For Jenkins API requests, use HTTP Basic authentication with the username and API token as the password.
Read the token from the local file; keep its value out of documentation and logs.
Authentication was verified on 2026-10-03: `GET /whoAmI/api/json` returned HTTP 200 with
`authenticated: true`, `name: mraysmit`, and `anonymous: false`.

### Run every suite on Jenkins

Open the [Qraft job](http://192.168.137.32:8080/job/Qraft/) and select **Build Now**.
The job loads the root [`Jenkinsfile`](../Jenkinsfile) and checks out its configured branch from
`https://github.com/mraysmit/qraft.git` on the `linux` node. The Linux fixture permission fix is included
in the published CI branch:

1. Verify Git, Maven, Docker, and Docker Compose. Download a checksum-verified SapMachine JDK 27
   into the job's `@tools` directory on its first run.
2. Run `mvn -B --fail-at-end -Dstyle.color=never clean install`: the default tests,
   coverage gates, and a freshly packaged runtime jar.
3. Run the tests tagged `e2e`, with `test.excludedGroups` cleared.
4. Run the tests tagged `docker` or `slow`, with `test.excludedGroups` cleared.
   Together with step 3, this covers every test the default build excludes; the Docker fixtures
   build their own image.

The suites run sequentially, and concurrent Qraft builds are disabled. An end-to-end failure still
allows the Docker suite to run, while a failed default build stops the dependent suites. Maven's exit
code is preserved through `tee`, and the build has a one-hour timeout.

Jenkins publishes the JUnit results and archives `logs/`, separate `reports/default`, `reports/e2e`,
and `reports/docker` directories, and the default suite's JaCoCo HTML/XML reports under `coverage/`.
It keeps 20 builds and artifacts from the last 10. Dependencies are cached in the job's `@repository`
directory, outside the source workspace; each build starts with a fresh checkout.

The job uses **Pipeline script from SCM**, **Git**, branch `*/main`, and script path `Jenkinsfile`.
Builds are started manually; no recurring trigger is configured. See [JENKINS.md](JENKINS.md) for the
job configuration, Linux fixture requirements, and run results. The memory corruption found in the
Jenkins VM on 2026-10-03 is resolved: the job was recreated on the current server on
2026-10-04, and JENKINS.md keeps the investigation as a historical record. The complete pipeline
first passed on the current server in build 4, on 2026-10-10 (JENKINS.md, "Current server setup").

## Run builds in a visible terminal, through `Tee-Object`

Run every build and test command in a terminal you can watch, and pipe its output through `Tee-Object`.
You see the progress live, and each run leaves a timestamped UTF-8 log in the
central `logs/` directory, which git ignores. This is the same command convention
as [PROJECT_STANDARDS.md, section 7](PROJECT_STANDARDS.md#7-standard-maven-verification-command):

```powershell
New-Item -ItemType Directory -Force .\logs | Out-Null
mvn -B "-Dstyle.color=never" install 2>&1 | Tee-Object -FilePath ".\logs\qraft-tests-$(Get-Date -Format 'yyyy-MM-dd_HH-mm-ss-fff').log"
$qraftBuildExitCode = $LASTEXITCODE
if ($qraftBuildExitCode -ne 0) { throw "Maven failed with exit code $qraftBuildExitCode" }
```

For every command below, create `logs/` first and check `$LASTEXITCODE` immediately
after the pipeline using the final two lines above. A successful `Tee-Object`
does not prove Maven passed. The shared Logback test configuration also writes
`logs/qraft-maven-tests-<timestamp>.log`; that application log complements the
captured Maven output.

The terminal is the VS Code integrated terminal. An assistant must not run builds or open a console unless
you specifically request it. It gives you the command to run in the VS Code terminal, through `Tee-Object`
as above, and then reads the results from the log. When you do request a run, the assistant uses one
visible console window, reuses it, and never runs a build hidden in the background.

## The three suites

| Suite | What it covers | Where | Time in tests* |
|---|---|---|---|
| **Default** | Unit and in-process integration tests: Raft on manual timers, gRPC on local ports, HTTP, storage, client, and a runtime start-up test | `src/test` | about 40 s (estimate) |
| **End-to-end** | Real server and client processes that wait out real TTLs and a real leader change | tagged `@Tag("e2e")` | about 50 s |
| **Docker** | Real three-server clusters in containers: restarts, partitions, clients. Since 2026-10-10 also every start command of `docker/` and both observability stacks | tagged `@Tag("docker")` | about 8.5 min |

\* Time spent running tests, measured 2026-09-29 before the end-to-end tests were separated. Compiling
and packaging come on top. The Docker suite's time is that of its Jenkins stage in build 19, on
2026-10-11.

The default build excludes the end-to-end and Docker suites, through `test.excludedGroups` in
`pom.xml`.

## While you work: run one test class

```powershell
mvn -B "-Dstyle.color=never" test "-Dtest=RaftNodeMembershipTest" 2>&1 | Tee-Object -FilePath ".\logs\qraft-tests-$(Get-Date -Format 'yyyy-MM-dd_HH-mm-ss-fff').log"
```

For more than one class:

```powershell
mvn -B "-Dstyle.color=never" test "-Dtest=RaftNodeMembershipTest,MembershipServiceTest" 2>&1 | Tee-Object -FilePath ".\logs\qraft-tests-$(Get-Date -Format 'yyyy-MM-dd_HH-mm-ss-fff').log"
```

Qraft is one Maven module, so there is no `-pl` and nothing to install first.

### Membership configuration parsing

`AppConfigRaftMembershipTest` exercises the real JSON parser and all six
membership getters. It covers omitted and partial settings, timing-dependent
cleanup defaults, non-default values, zero promotion limits, positive cleanup
limits, disabled cleanup, unknown fields, malformed types, duplicate keys and
numeric representation bounds. Membership and cleanup must be objects when
present; explicit null is rejected. These parser tests do not prove server
wiring, heartbeat-round conversion or automatic removal behavior.

```powershell
mvn -B "-Dstyle.color=never" test "-Dtest=AppConfigRaftMembershipTest" 2>&1 | Tee-Object -FilePath ".\logs\qraft-membership-parser-$(Get-Date -Format 'yyyy-MM-dd_HH-mm-ss-fff').log"
$qraftBuildExitCode = $LASTEXITCODE
if ($qraftBuildExitCode -ne 0) { throw "Maven failed with exit code $qraftBuildExitCode" }
```

On 2026-10-15 the initial run recorded 61 cases and 59 failures because
`membership` was not accepted. After implementation and expanded value
assertions, all 64 focused cases passed. The captured Maven output has no
ERROR entry, exception trace or ANSI escape, and the application log is empty.
The focused run reported JaCoCo execution-data/class mismatches from earlier
builds; coverage and full-suite verification remain pending. See the
[Step 5 record](../docs-design/task-list-raft-membership-changes-2026-10-12.md#step-5-failed-server-cleanup)
for the retained output and remaining runtime work.

### State-loop and blocking-executor changes

Start with the executor, ownership and package-boundary tests:

```powershell
mvn -B "-Dstyle.color=never" test "-Dtest=BlockingTaskExecutorTest,RaftStateLoopTest,QraftServerLifecycleTest,PackageDependencyTest" 2>&1 | Tee-Object -FilePath ".\logs\qraft-tests-$(Get-Date -Format 'yyyy-MM-dd_HH-mm-ss-fff').log"
$qraftBuildExitCode = $LASTEXITCODE
if ($qraftBuildExitCode -ne 0) { throw "Maven failed with exit code $qraftBuildExitCode" }
```

The tests cover worker and queue bounds, overload rejection, shutdown outcomes,
completion dispatch, timer cleanup and MDC/OpenTelemetry propagation. Protocol and
recovery tests borrow both resources from `RaftExecutorsFixture`, which closes blocking
work before its completion loop. Production ownership belongs to the server composition root.

`ShutdownCoordinatorTest` owns only a `RaftStateLoop`, created in `@BeforeEach`
and stopped in `@AfterEach`. It awaits shutdown futures with bounded `get` calls
and asserts their results or failure causes on the test thread. An incomplete
future holds the drain phase while the test checks that work is refused; timeout
tests use incomplete hook futures rather than artificial delays. State-loop
teardown is awaited before the intentional-error audit closes the test's scope.
The former `JavaTestContextHelper` and `JavaRuntimeExtensionHelper` are removed;
tests own the required execution resources explicitly and assert completed
future outcomes on the test thread.

| Contract | Regression coverage |
|---|---|
| Worker and queue bounds; invalid limits; rejection after stop | `BlockingTaskExecutorTest` |
| Logging and tracing context through successful and failed work; restoration afterward | `BlockingTaskExecutorTest`, `RaftStateLoopTest` |
| Queued cancellation; task failure retains its cause; later work still runs | Both executor test classes |
| Graceful shutdown waits for delivered results; forced shutdown settles operations before notifying the owner | Both executor test classes |
| Timer registration races, timer-future cancellation, periodic context and schedule cleanup | `RaftStateLoopTest` |
| Rejected or abandoned completion dispatch cannot strand a worker result | Both executor test classes |
| Blocking work stops before the loop; failed cleanup still releases the loop and telemetry; interrupts are preserved | `QraftServerLifecycleTest` |
| Raft ordering, recovery and protocol behavior on the separated executors | Existing `RaftNode*Test`, `RaftTransitionSequencer*Test`, gRPC and lifecycle tests |

After the small focused run passes, run the affected concurrency and lifecycle tests
five times consecutively, executing this same command separately each time:

```powershell
mvn -B "-Dstyle.color=never" test "-Dtest=BlockingTaskExecutorTest,RaftStateLoopTest,QraftServerLifecycleTest,RaftNode*Test,RaftTransitionSequencer*Test,ShutdownCoordinatorTest,GrpcRaft*Test,ClusterBootstrapTest" 2>&1 | Tee-Object -FilePath ".\logs\qraft-executor-repeat-$(Get-Date -Format 'yyyy-MM-dd_HH-mm-ss-fff').log"
$qraftBuildExitCode = $LASTEXITCODE
if ($qraftBuildExitCode -ne 0) { throw "Maven failed with exit code $qraftBuildExitCode" }
```

Keep each capture. A failed
run requires investigation; a later pass does not erase it. For the executor replacement,
use `clean install` with the standard capture options to remove deleted runtime and test
classes from `target/`. Then run the runtime suites below, including `slow` for Phase 7's
full-suite exit, on a fresh image. Audit all retained output and logs under the
intentional-error rules below. Passing assertions alone does not close the task.

For that full runtime gate, include every default-excluded group:

```powershell
mvn -B "-Dstyle.color=never" test "-Dgroups=docker,e2e,slow" "-Dtest.excludedGroups=" 2>&1 | Tee-Object -FilePath ".\logs\qraft-tests-$(Get-Date -Format 'yyyy-MM-dd_HH-mm-ss-fff').log"
$qraftBuildExitCode = $LASTEXITCODE
if ($qraftBuildExitCode -ne 0) { throw "Maven failed with exit code $qraftBuildExitCode" }
```

### Fencing diagnostics

For fencing diagnostics, the focused classes are `RaftTransitionSequencerTest`,
`RaftNodeApplyFailureTest`, `RaftNodeConfigurationTest`,
`RaftNodeMetadataSequencingTest` and `HttpApiServerTest`. They check first-cause
retention, one ERROR announcement with caller context, the nullable `fenceReason`
status field for healthy, transition-fenced and startup-fenced nodes, and the
boundary between a rejected bootstrap and uncertain bootstrap persistence.
Repeat the affected Raft and HTTP tests five times before the final build:

```powershell
mvn -B "-Dstyle.color=never" test "-Dtest=RaftTransitionSequencer*Test,RaftNode*Test,HttpApiServer*Test" 2>&1 | Tee-Object -FilePath ".\logs\qraft-fence-repeat-$(Get-Date -Format 'yyyy-MM-dd_HH-mm-ss-fff').log"
$qraftBuildExitCode = $LASTEXITCODE
if ($qraftBuildExitCode -ne 0) { throw "Maven failed with exit code $qraftBuildExitCode" }
```

### Phase 7 verification record, 2026-10-13

The committed executor, test-helper and fencing changes (`7a9108f`, `d876349`,
`f737d6b`) completed their verification locally. Five consecutive affected Raft
and HTTP runs passed 293 tests each. The final default `install` passed 960 tests
and every coverage gate; the runtime gate passed 42 `docker,e2e,slow` tests
against the freshly packaged jar and rebuilt image. Neither final suite had a
failure, error or skip.

Retained Maven captures are
`logs/qraft-fence-final-install-2026-10-13_19-03-35-146.log` and
`logs/qraft-fence-final-runtime-2026-10-13_19-04-56-948.log`. The recorded final
audit covered 322 retained logs, including 163 Docker archives: all 2,214 ERROR
headers and 2,846 exception headers were attributed, including one flagged
lock-contender uncaught exception. No unflagged error or exception header
remained. Counts include duplicate captures. Earlier failed runs are retained
separately with their causes and fixes; passing test totals did not erase them.
See [the Phase 7 record](archive/task-list-single-pom-and-quorus-removal-2026-10-12.md#phase-7-replace-the-vertx-shaped-async-layer)
for TDD evidence, capture names and the native-container audit follow-up.
These are dated results, not expected counts for future runs or Jenkins results.

## Before you call a change done: `mvn install`

```powershell
mvn -B "-Dstyle.color=never" install 2>&1 | Tee-Object -FilePath ".\logs\qraft-tests-$(Get-Date -Format 'yyyy-MM-dd_HH-mm-ss-fff').log"
```

This runs the default suite, then the coverage gates: every package must keep at least 60%
line coverage (JaCoCo). The gates run in the `verify` phase, so **`mvn test` does not check coverage**, and
a change can pass `mvn test` but break `mvn install`. `mvn install` also packages
`target/qraft.jar`, the executable jar the Docker suite needs.

**One executable jar.** Until 2026-10-03 `qraft-controller` shaded its
dependencies into its own jar as well. Without `clean`, that step started from the previous shaded jar and
kept an old dependency's classes, and `qraft-runtime.jar` inherited them. The unit tests ran against the
new version, so nothing failed: only the packaged jar, and so the Docker suite and any deployment, ran the
old one. It was found when RaftLog went from 1.4.0 to 1.4.1. A plain `mvn install` now packages the
versions the root POM names; `clean` is not needed after a dependency change. To check what a jar holds:

```bash
unzip -p target/qraft.jar META-INF/maven/io.github.mraysmit/raftlog-core/pom.properties
```

## When the change touches the runtime: the end-to-end and Docker suites

Run them when a change touches server or client start-up, health checks and their TTLs, ports,
configuration files, the POM, or the Dockerfiles. They aren't needed for every change. Run them straight
after `mvn install`, together:

```powershell
mvn -B "-Dstyle.color=never" test "-Dgroups=docker,e2e" "-Dtest.excludedGroups=" 2>&1 | Tee-Object -FilePath ".\logs\qraft-tests-$(Get-Date -Format 'yyyy-MM-dd_HH-mm-ss-fff').log"
```

Or only the end-to-end suite, which needs no Docker:

```powershell
mvn -B "-Dstyle.color=never" test "-Dgroups=e2e" "-Dtest.excludedGroups=" 2>&1 | Tee-Object -FilePath ".\logs\qraft-tests-$(Get-Date -Format 'yyyy-MM-dd_HH-mm-ss-fff').log"
```

- Docker must be running. The suite builds the `qraft-runtime:test` image once, from
  `target/qraft.jar`, and refuses a jar older than the sources. So run `mvn install`
  first. `docker/build-runtime.ps1` (or `.sh`) only packages that jar, so you don't need it after
  `mvn install`.
- Both options are needed: `-Dgroups` selects the suites, and the empty `-Dtest.excludedGroups=` lifts
  the default exclusion.
- Don't run another Maven build in the same working tree while the Docker suite runs. It overwrites the
  classes and jar under test.

## Deploying to Docker

There are two ways onto Docker, and they build differently.

**1. The Docker tests deploy themselves.** You never deploy by hand before running them. After `mvn install`:

1. `SharedDockerClusterFixture` checks that `target/qraft.jar` exists and is newer than the
   sources.
2. It builds the `qraft-runtime:test` image once per run, with
   `docker compose -f src/test/resources/docker-compose-build-image.yml build`.
3. Each test class starts its clusters from the compose files in `src/test/resources`,
   using the `docker/config/*-acceptance` configs, and removes them when it finishes.

**2. A cluster you start by hand**, to try something out or watch it run. Run these from `docker/`:

```powershell
.\start.ps1 cluster                # one server with a client: http://localhost:8080
.\start.ps1 multinode              # three servers: http://localhost:8081-8083 (compose/docker-compose-cluster.yml)
.\start-quick.ps1 cluster 3node    # the same three servers
.\start-quick.ps1 cluster 5node    # five servers:  http://localhost:8081-8085 (compose/docker-compose-5node.yml)
.\start.ps1 status                 # the running Qraft containers
.\start.ps1 stop                   # stop every cluster either script started
```

Only one cluster runs at a time: they share container names, so stop one before starting another.
Each `cluster` or `multinode` command first runs `docker/build-runtime.ps1`. It packages the runtime
jar with `package -DskipTests` when the POM or a production source is newer than the jar, and does
nothing when the jar is current. The command then starts the containers with
`docker compose ... up -d` and returns. Follow a cluster's logs with
`docker compose -f compose/<file>.yml logs -f`. `start.sh` and `start-quick.sh` are the shell
equivalents.

`DockerStartCommandsTest`, in the Docker suite, runs every one of these commands and those of
`start-observability`: the shell scripts on Linux, and so on Jenkins, and the PowerShell scripts on
Windows. It runs them in a Compose project of its own, `qraft-start-commands`, with
`PUBLISHED_PORT=0`, so it publishes on free host ports and removes only its own containers and
volumes. It fails while a cluster started by hand is running, because the container names are
taken.

**The whole process for a runtime change:**

1. `mvn install`, which runs the default suite and the coverage gates, and packages the runtime jar.
2. `mvn test "-Dgroups=docker,e2e" "-Dtest.excludedGroups="`, the
   end-to-end and Docker suites, which build their own image from that jar.
3. Optional: `docker\start.ps1 multinode`, to watch a hand-started cluster. Straight after step 1 the
   jar is current, so it starts the containers without packaging again.

## Mutation testing with PIT

A passing suite shows that the code works. It does not show that the tests would notice if a safety
check were lost: the suite passes just as well when no test reaches the check. Mutation testing asks
that question. [PIT](https://pitest.org) is the tool.

**On ice since 2026-10-11, at the owner's decision.** PIT is configured and has passed its dry runs.
An earlier whole-class analysis was aborted after two hours without a report; its
history is recorded in [JENKINS.md](JENKINS.md#current-server-setup-2026-10-04).
No further mutant is to be run against a test until the owner asks. The owner sees no
value in it for now: Qraft's test coverage is high, and its tests are written test-first. Do not run
it, extend it, or propose it. What follows describes the configuration as it was left.

In Qraft, PIT is a targeted audit of the Raft-membership implementation. It is not a mutation test of
the whole application.

At a glance:

| | |
|---|---|
| Maven profiles | `mutation-testing`, with one batch profile, `pit-batch-01` to `pit-batch-05`, in `pom.xml` |
| Command | `mvn "-Pmutation-testing,pit-batch-01" test-compile org.pitest:pitest-maven:mutationCoverage` |
| Dry run | the same command with `"-Dpit.dryRun=true"` |
| On Jenkins | the `Qraft` job with `MUTATION_TESTING` set to `dry-run` or `run`, and `MUTATION_BATCH` set to the batch |
| Class mutated | `MembershipService`, in `dev.mars.qraft.raft`: 39 mutants, in five batches of at most ten |
| Report | `target/pit-reports/index.html`; on Jenkins, `reports/pit/` in the build's artifacts |
| Part of a normal build | No. Nothing runs it unless the profiles and the goal are named |

### What PIT does

```text
Compile Qraft and its tests
        ↓
Run the eligible JUnit tests for coverage
        ↓
Generate mutations in the configured Raft class
        ↓
Select the tests that cover each changed instruction
        ↓
Run those tests against each mutant
        ↓
Write HTML and XML reports
```

A **mutant** is the compiled production code with one small change. Suppose `RaftNode` holds a check
like this:

```java
if (!configuration.isVoter(serverId)) {
    return;
}
```

PIT may generate the mutant in which the condition is reversed:

```java
if (configuration.isVoter(serverId)) {
    return;
}
```

PIT makes the change in memory, in a worker JVM. The source and the compiled files on disk are not
modified, and no copy of the code is kept. It runs the tests that execute that instruction, the fastest
first, and gives the mutant one of these results:

- `KILLED`: a test failed. The tests tell the correct rule from the mutated one.
- `SURVIVED`: every covering test passed. The suite does not tell the two apart.
- `NO_COVERAGE`: no test executed the instruction.
- `TIMED_OUT`: the mutant made a test run past its time limit, as an endless loop does. PIT ends it.

**PIT is a diagnostic, not a score to reach.** Good tests kill most mutants, and that result is the
evidence that they are good. A surviving mutant is a prompt to look at that line, not an instruction to
write a test. Some survivors are equivalent, which means the change makes no difference that anything
could observe, and some guard nothing that matters. Each survivor is read and judged, and the judgement
is recorded.

### What is mutated, and with which tests

Mutation testing is too expensive for a normal build, so PIT has a Maven profile of its own,
`mutation-testing`, in `pom.xml`, and is bound to no phase. The profile's `<configuration>` holds
everything.

**PIT runs one batch of at most ten mutants at a time.** A run of unknown length is not started: the
smallest batch is run and measured first, and the next step is estimated from that measurement, not
guessed.

It mutates one class, `MembershipService`, which has 39 mutants. PIT can leave methods out, but it
cannot name the methods to keep, and it cannot be told to take ten mutants and then the next ten. So a
batch is the class without the methods of the other batches, and where one method has more than ten
mutants, with one kind of mutation. Each batch is a profile that names the methods it leaves out and
its mutators:

| Batch | Profile | What it mutates | Mutants |
|---|---|---|---:|
| 01 | `pit-batch-01` | `join`, its two lambdas, the constructor, and `response` | 9 |
| 02 | `pit-batch-02` | `forward`, its lambda, and `outcome` | 7 |
| 03 | `pit-batch-03` | the lambdas of `outcome` and of `remove` | 10 |
| 04 | `pit-batch-04` | `remove`: its conditions reversed | 7 |
| 05 | `pit-batch-05` | `remove`: its return values replaced | 6 |

The sizes are from a dry run of each batch on 2026-10-11. A batch's fixed cost is the coverage pass,
about one minute, which runs the whole default suite once. A mutant that a test fails on at once costs
far less than a second. One that makes a test hang costs most of a minute, until PIT's timeout ends it.

**`RaftNode` is not mutated.** On 2026-10-11 it was run whole with two other classes, 1,187 mutants,
and aborted after two hours with no report. A mutant that removes a step from Raft code often makes a
test wait for something that never comes, not fail, and PIT ended 160 such mutants in those two hours.
At that rate the run takes days. It is not to be proposed again as a whole.

PIT also does not mutate `ClusterBootstrap`, Raft storage and snapshots, the gRPC transport, replicated
state, the server's HTTP API, the client, or the runtime.

There is no `targetTests`: PIT makes a coverage pass and finds which tests reach each mutant.
`excludedGroups` keeps out the `docker`, `slow`, and `e2e` tests. PIT may run one test many times, so
tests that start containers or wait out real time would make a run too long. What is left is the
default suite.

The check of intentional errors stays on. A mutant that makes a test log an undeclared error fails that
test, and so is killed. That is meant: in Qraft, "no error without a flag" is part of what a test
observes.

The rest of the configuration: `toString`, `equals`, and `hashCode` are never mutated; four threads
work at once; and a test on a mutant may take 1.5 times its normal time plus 4 seconds before PIT ends
it. `withHistory` is not set: PIT 1.30.0 refuses it without a separate history plugin, which the
open-source release does not include.

### Running it

A PIT run goes to Jenkins: start the `Qraft` job with `MUTATION_TESTING` set to `dry-run` or `run` and
`MUTATION_BATCH` set to the batch, `01` to `05`. The job then runs PIT on that batch instead of the
three suites and archives the report under `reports/pit/`. The stage has a limit of 20 minutes: a batch
that runs longer has gone wrong and is stopped.

The commands it runs are these. Run by hand, they follow the rule above: in a terminal you can watch,
through `Tee-Object`, with `$LASTEXITCODE` checked at once.

A dry run compiles the code, discovers the tests, collects coverage, and generates the batch's mutants.
It does not run tests against them. It shows that the configuration works and how many mutants the
batch holds:

```powershell
mvn -B "-Dstyle.color=never" "-Pmutation-testing,pit-batch-01" "-Dpit.dryRun=true" test-compile org.pitest:pitest-maven:mutationCoverage 2>&1 | Tee-Object -FilePath ".\logs\qraft-pit-$(Get-Date -Format 'yyyy-MM-dd_HH-mm-ss-fff').log"
$qraftBuildExitCode = $LASTEXITCODE
```

The analysis of the batch:

```powershell
mvn -B "-Dstyle.color=never" "-Pmutation-testing,pit-batch-01" test-compile org.pitest:pitest-maven:mutationCoverage 2>&1 | Tee-Object -FilePath ".\logs\qraft-pit-$(Get-Date -Format 'yyyy-MM-dd_HH-mm-ss-fff').log"
$qraftBuildExitCode = $LASTEXITCODE
```

### The report, and what fails a run

PIT writes `target/pit-reports/index.html` and `target/pit-reports/mutations.xml`. The HTML report
opens on `MembershipService` and shows, for each mutated line of the batch, the
mutation PIT applied, whether it was killed and by which test, and whether it survived or had no
coverage. The XML file is for a program, and is what Jenkins archives beside it.

No threshold is set, so a surviving mutant does not fail a run, and neither does a low score. A run
still fails on an error in PIT's configuration, on a test that fails or crashes in the coverage pass,
and on an error while PIT runs.

The order of work:

1. Run batch 01 on Jenkins and measure it.
2. Review its surviving and uncovered mutants, and record for each whether it is a real gap, an
   equivalent mutant, or a change that does not matter.
3. Only then run the next batch, with its time estimated from the batches before.

## Making runs faster

- Name only the classes you need (`-Dtest`) while you work, and keep `mvn install` for
  the end.
- Use `mvnd` in place of `mvn` for repeated small runs.
- Surefire runs the test classes one after another in one JVM. `-DforkCount=2` runs them in two
  JVMs. It is not the default, because it has not been tried on this suite yet: the in-memory Raft
  simulator keeps static state, which is safe across JVMs but not across threads.

## Results

- Test results: `target/surefire-reports/`. One XML and one text file per class, with the time
  each class took.
- Coverage: `target/site/jacoco/index.html`, written by `mvn install`.
- What an investigation with tests found, and what it did not: [TEST-RESULTS.md](TEST-RESULTS.md).

## Intentional error flags and log auditing

Every error caused deliberately by a test must carry an explicit flag naming the failure and the test
responsible. Every other error requires remediation. A successful Maven exit code or passing assertions
alone do not establish that the logs are acceptable.

The flags preserve the event's ERROR severity, message, diagnostic fields, and stack trace. They do not
filter, suppress, or downgrade the event. A flag on an exception header applies to its following stack
trace; individual frames and `Caused by:` lines are retained without repeating the flag on each line.

| Flag | Meaning | Attribution |
|---|---|---|
| `*** INTENTIONAL ERROR: <entry>, caused by <test> ***` | The test deliberately arranged a condition that makes production code report an error. | Usually `TestClass#testMethod`; Docker capture uses the test class. |
| `*** INJECTED FAILURE: <entry>, injected by <test> ***` | The test supplied an explicitly marked failure through `InjectedFaultFixture`. | The running test that owns the logging scope. |

The following extracts come from the verified 2026-10-08 run. Filenames and line numbers identify the
local, git-ignored logs; the extracts are included here so the examples remain available without those files.

### Declared intentional errors

The definitions in
[`IntentionalErrorFixture`](../src/test/java/dev/mars/qraft/testing/fault/IntentionalErrorFixture.java)
specify the exact logger, level, and complete message pattern. A test declares its expected error with
`IntentionalErrorsHelper.expect` before causing it. The declaration belongs to the current test's scope;
it does not permit matching errors everywhere in the suite.

The default declaration requires at least one occurrence. `expect(error, times)` requires exactly that
many. Missing occurrences or counts outside the declared bounds fail the audit. Leader-loss tests use
`expect(RAFT_PEER_UNREACHABLE, formerLeader)` to accept RPC failures only for the deliberately stopped peer.
A different peer, logger, level, or message remains unexpected.

Cluster teardown also deliberately closes peers while a surviving leader may still contact them.
`IntentionalErrorsHelper.expectPeerShutdownDuringCleanup(peer)` declares that specific peer immediately
before its runtime is closed, after the functional assertions. It permits zero occurrences because cleanup
does not guarantee an RPC is in flight. Only the same exact peer-unreachable logger, level, and message
signatures match; earlier errors, other peers, and subsequent tests remain unaccepted. Once cleanup is
declared for that peer, its matching failures never count toward functional expectations, whether the
expectation names that same peer, another peer, or no peer, and whether its count is exact or at least once.
Retry cleanup uses the same close path as final teardown and removes successfully closed runtimes from
the cleanup registry. No allowance is added for the last live cluster peer or a runtime already closed.

Source: `logs/qraft-tagged-2026-10-08_13-13-20-497.log`, line 5373. The stopped peer is `node-c`:

```text
13:21:09.381 [qraft-state-loop] ERROR *** INTENTIONAL ERROR: RAFT_PEER_UNREACHABLE, caused by HealthPropagationEndToEndTest#aRenewalHeldPastItsDeadlineAcrossALeaderChangeLandsInsideTheNewLeadersGraceSoTheCheckNeverExpires *** dev.mars.qraft.controller.raft.RaftNode [node-b] [LEADER/2] [bbc5514d-31cd-4743-83f5-0d3639e0fd7d] [AppendEntries] - Raft peer node-c became unreachable during AppendEntries
```

### Injected failures

`InjectedFaultFixture` names an injected-failure entry in the same definitions. The logging helpers
recognise that marker in the logged exception itself, its causes, or its suppressed-exception chain.
Recognition is automatic; a test can additionally declare an expected count with `expect`.

Source: `logs/qraft-default-2026-10-08_13-11-50-284.log`, lines 2323-2325:

```text
13:12:37.820 [main] ERROR *** INJECTED FAILURE: SHUTDOWN_HOOK_FAILURE, injected by TimeoutHandlingTests#synchronousCriticalHookFailureIsReportedAsynchronously *** d.m.q.controller.lifecycle.ShutdownCoordinator - Critical shutdown hook 'node-stop' failed with InjectedFaultFixture
dev.mars.qraft.testing.fault.InjectedFaultFixture: node stop failed before returning
	at dev.mars.qraft.controller.lifecycle.ShutdownCoordinatorTest$TimeoutHandlingTests.synchronousCriticalHookFailureIsReportedAsynchronously(ShutdownCoordinatorTest.java:311)
```

### Helper subprocesses

A separate JVM must establish its own scope with `IntentionalErrorsHelper.inSubprocess`, unless it never
returns (see the end of this section). Its body must
return through the audit before calling `System.exit`; otherwise its errors and expectation counts would
go unchecked. `DirectoryLockProcessFixture` receives the calling test's name and an `owner` or `contender`
role. Only the contender declares exactly one `WAL_DIRECTORY_ALREADY_LOCKED` error. The parent test checks
the contender's exit code `73` and the flagged error, and checks that the owner remains healthy and exits
successfully. An audit failure cannot pass merely because the contender exited unsuccessfully.

Source: `logs/qraft-maven-tests-2026-10-08_13-13-09.log`, line 3:

```text
2026-10-08 13:13:09.328 [wal-executor] ERROR *** INTENTIONAL ERROR: WAL_DIRECTORY_ALREADY_LOCKED, caused by RaftStorageProcessLockTest#secondJvmCannotOpenDirectoryWhileOwnerRemainsHealthy/contender *** dev.mars.raftlog.storage.FileRaftStorage - Cannot acquire exclusive lock at C:\Users\markr\AppData\Local\Temp\junit-709376945766950860\raft.lock: another process holds the lock
```

A helper JVM that never returns cannot audit itself: a crash writer halts at its checkpoint, and
`CrashedClientExpiryEndToEndTest` kills its client. Its parent test audits it instead. Once the JVM has ended,
the test passes its complete console output to `SubprocessOutputAuditHelper.requireNoErrors`. An ERROR line,
a Logback status error, or an uncaught exception in that output fails the calling test, and the failure
quotes each error with its stack trace. These JVMs declare no intentional error, so a flag does not excuse
one. The three crash-writer recovery tests call it when their writer exits, and
`CrashedClientExpiryEndToEndTest` calls it in its teardown. Such a JVM also writes its own
`logs/qraft-maven-tests-<timestamp>.log`, which the review of retained logs covers.

### Docker archives and uncaught exceptions

Docker test classes declare their expected errors with `@ExpectedDockerErrorsHelper`. At class teardown,
`DockerLogExtensionHelper` audits captured output against those declarations. Matching entries are
flagged in `logs/docker/<TestClass>/`, retaining their original timestamps and stack traces. Recognised
ERROR messages are also replayed into the main test log with class-level attribution. The replay's
timestamp and `[main]` thread describe the audit reprinting the message, rather than the original event.

An uncaught container exception is a separate audit check. It is flagged only when it matches a declared
rethrow signature and the exact failure message of a recognised error from the same container or helper
process. The ERROR may follow the rethrow when Docker merges stdout and stderr, or have been drained
in an earlier capture of that same source. The current class must still declare that error; an error from
another source cannot excuse the rethrow. For the lock conflict, matching requires the expected `CompletionException` and
`FileRaftStorage$StorageException` types and the same directory and failure message. Cleanup INFO messages
between the original error and the rethrow do not prevent the match. Compose and timestamp prefixes
on uncaught headers are preserved and audited. A missing matching error, different
directory or cause, undeclared exception, or malformed uncaught-exception header fails the audit and is
archived without an intentional flag. The recognised rethrow is not replayed as a second ERROR event.

An event below ERROR that carries a stack trace is a third audit check. A definition names its own
level, so a class can declare such an event. The audit then flags it like a declared ERROR and replays
it into the main test log at its own level. Any other event below ERROR that is followed by stack-trace
lines fails the audit and is archived without a flag. An event below ERROR without a stack trace is not
checked.

The one definition of this kind is `RAFT_PEER_NAME_UNRESOLVED`. A server whose peer the test has killed
or disconnected can no longer resolve that peer's name, and gRPC reports each failed lookup as a WARN
whose message holds the lookup's stack trace. The definition names the gRPC logger, the WARN level, and
the whole first line, with the same host in the channel, the description, and the exception. Every
Docker class that declares `RAFT_PEER_UNREACHABLE` declares it too, and a test checks that.

Docker merges stderr into stdout, so an event can land inside an uncaught exception's trace. Frames that
follow an uncaught exception therefore belong to that exception, which has its own check above.

The observability programs use native log formats. `DockerLogCaptureHelper` checks their
`level=error`/`level=fatal` and tab-delimited error levels during archive auditing;
`DockerStartCommandsTest` also checks live output before stopping them. Their errors fail the test.
The one previously approved startup allowance is declared as `TEMPO_METRICS_WAL_STARTUP`:
Tempo's exact `msg="error tailing WAL" err="failed to find segment for index"` signature.
It must occur only once per captured source, with "Done replaying WAL" later in the same output
for the same tenant, remote name and endpoint. Missing, earlier or mismatched recovery, repeated
errors, and every other native error fail. The archive flags this event explicitly with its
definition and Docker test class, preserving its original error level and line, and replays it
into the Maven/application log with the same attribution. Tempo 2.3.1 starts the remote watcher
before opening its metrics WAL; the first span can expose this startup race.

### A warning of a library, caused on purpose inside the test JVM

A definition can also name a library's logger and a level below ERROR for an event inside the test JVM.
`GRPC_CONNECTION_ACCEPTED_AS_SERVER_STOPS` is Netty's WARN "Failed to initialize a channel", which
carries an `IllegalReferenceCountException`. A gRPC server that is stopping releases its shared
resources and then marks itself terminated, and a connection that a worker thread prepares between
those two steps is closed with this warning. One test of `GrpcRaftServerTest` places a connection
exactly there and declares the warning once. Any other test that logs it has a defect in its teardown:
see the rule on gRPC connections below.

Source: `logs/docker/DockerDurableRestartTest/lock-contender.log`, lines 82 and 95-97. These are nonadjacent
extracts; intervening cleanup messages and the first error's trace are omitted:

```text
2026-10-08 05:18:17.122 [wal-executor] ERROR *** INTENTIONAL ERROR: CONTROLLER_STORAGE_ALREADY_LOCKED, caused by DockerDurableRestartTest *** d.mars.qraft.controller.QraftControllerService - Failed to initialize Raft storage: Cannot acquire exclusive lock on WAL directory: /app/data. Another process may be using this storage.
Exception in thread "main" *** INTENTIONAL ERROR: CONTROLLER_STORAGE_ALREADY_LOCKED, caused by DockerDurableRestartTest *** java.util.concurrent.CompletionException: dev.mars.raftlog.storage.FileRaftStorage$StorageException: Cannot acquire exclusive lock on WAL directory: /app/data. Another process may be using this storage.
	at java.base/java.util.concurrent.CompletableFuture.wrapInCompletionException(Unknown Source)
	at java.base/java.util.concurrent.CompletableFuture.encodeThrowable(Unknown Source)
```

The corresponding replay appears in `logs/qraft-tagged-2026-10-08_13-13-20-497.log`, line 983:

```text
13:19:54.442 [main] ERROR *** INTENTIONAL ERROR: CONTROLLER_STORAGE_ALREADY_LOCKED, caused by DockerDurableRestartTest *** d.mars.qraft.controller.QraftControllerService - Failed to initialize Raft storage: Cannot acquire exclusive lock on WAL directory: /app/data. Another process may be using this storage.
```

### What makes verification fail

The JUnit extension opens a scope around each test, including its teardown, and an enclosing scope around
each test class. The Logback check records undeclared ERROR events and events carrying an undeclared
exception at any level. Problems logged outside a scope are also checked when a class closes. The extension
fails the test or class for those problems or unmet expected counts, even if its normal assertions passed.
It also refuses a test runtime without the required started logging check attached to the root logger.
Docker auditing separately rejects undeclared or unparseable ERROR entries, unmatched uncaught exceptions,
and undeclared events below ERROR that carry a stack trace.

Review the captured Maven output, application logs, subprocess logs, and Docker archives. Check that error
headers carry the expected flag and identify the responsible test, and that their messages and traces
describe the failure the test deliberately caused. Every unflagged error requires investigation and
remediation; declaring a broad expectation merely to make an unrelated failure pass is not acceptable.

The earlier 2026-10-08 verification, from which the extracts above were taken, ran 845 default tests and
31 tagged tests with no failures, errors, or skips;
coverage checks passed. Reviewing 80 log files found 543 flagged ERROR entries, 322 exception headers
belonging to flagged events, and one flagged uncaught exception, with no unflagged errors or exceptions.
The ERROR count includes duplicate copies across console captures, application logs, and Docker archives;
it is not a count of unique failures. These figures describe that run, not fixed expectations for future runs.

Phase 3's first Raft/state slice verification on 2026-10-08 passed 854 default tests, all coverage gates, and all 31 tagged
tests on a fresh runtime image. Its captures are `qraft-phase3-final-clean-2026-10-08_16-03-01-464.log` and
`qraft-phase3-final-tagged-2026-10-08_16-04-37-245.log`. Reading all 80 logs from that verification window
found 581 flagged ERROR headers, 357 exception headers belonging to flagged events, one flagged uncaught
rethrow, and zero unflagged errors or exceptions. Earlier failed runs remain as evidence of the defects
remediated during verification; their failures are recorded in the Phase 3 implementation task list.

The subsequent shutdown/audit defect follow-up passed 75 focused tests and two further runs of all 29
concurrency tests. Its clean build passed 863 default tests and all coverage gates; its fresh-image
`docker,e2e,slow` run passed 31 tagged tests, for 894 tests with zero failures, errors, or skips.
Captures: `qraft-review-default-2026-10-08_17-35-33-237.log` and
`qraft-review-tagged-2026-10-08_17-37-10-226.log`. All 86 logs written during that successful verification
window were audited: 567 flagged ERROR headers, 377 exception headers under flagged events, one flagged
uncaught rethrow, and zero unflagged errors or exceptions. The focused RED run is retained separately as
diagnostic evidence of the defects; it is not counted as successful verification.

## Phase 3 package and startup contracts

- `PackageDependencyTest` scans compiled production references in the six final package trees:
  `common`, `client`, `server`, `raft`, `state`, and `runtime`. Compiled mutation fixtures
  prove that both Raft and State dependencies on server HTTP fail, alongside other forbidden edges.
- `ProtocolPackageTest` checks generated Java ownership and unchanged `qraft.raft.RaftService` /
  `qraft.api.DistributedStateService` names. `LegacyCatalogFixtureTest` retains immutable command
  and snapshot bytes and hashes.
- `RuntimeLoggingTest` starts each real mode in a fresh JVM through
  `RuntimeLoggingProcessFixture`, using production Logback. It checks the configured directory
  and mode filenames from the first configuration event: `qraft-server.log/json` or
  `qraft-client.log/json`. Child console and file logs are retained under
  `logs/phase3-runtime-process-<mode>-<id>/` and reprinted in the Maven capture.
- `LoggingConfigurationTest` checks UTF-8, available appenders, no settings that drop errors,
  and compressed rolling files under `archive/`.
- `TelemetryConfigTest` exercises the real enabled SDK, disabled telemetry, and resource cleanup
  after Prometheus binding or global SDK registration fails.
- `DockerDeploymentContractTest` covers server DNS/configuration/dashboard names, dynamic fixture
  service names, launcher dispatch, and the client's `client` / `servers`
  configuration objects. Its Docker-tagged contract validates every maintained Compose model.
- Historical log extracts above retain their original package/class names. Current production
  classes use the final packages; the intentional-error flag format and attribution rules are unchanged.

Whole Phase 3 verification completed on 2026-10-08: 876 default tests with all coverage gates and
32 fresh-image `docker,e2e,slow` tests passed, with zero failures, errors, or skips. A separate 47-test
lifecycle/concurrency repeat also passed. Final captures are
`logs/qraft-phase3-verified-default-2026-10-08_19-28-47-852.log` and
`logs/qraft-phase3-verified-tagged-2026-10-08_19-30-33-972.log`; the repeat is
`logs/qraft-phase3-complete-repeat-2026-10-08_19-15-23-119.log`.
Actual Surefire XML is retained in `logs/phase3-final-evidence/default/` and `tagged/`, independently
confirming 908 tests in 139 distinct classes. Preserve separate copies because later suites can
overwrite reports for a class with both default and tagged cases.

The final successful-window audit covered 87 retained Maven, application, subprocess, and Docker logs:
449 explicitly flagged ERROR headers, 301 exception headers attributed to flagged events, one flagged
uncaught rethrow, and zero unflagged errors or exceptions. Counts include duplicate captures. Both
fresh-JVM JSON logs were also parsed: 54 events with no ERROR or stack trace. The earlier failed Docker
run revealed two migrated logger signatures still naming the old server logger. After failing
regressions, the definitions now use `QraftServerService.class.getName()` with their existing exact
message patterns and severity; all nine durable-restart cases and their log audit passed in the final run.

## Rules for tests

- **Every error must be explicitly flagged intentional.** The logging audit fails on an undeclared ERROR
  or an event carrying an undeclared exception, even when the test assertions pass. Every other error
  requires remediation. See [Intentional error flags and log auditing](#intentional-error-flags-and-log-auditing)
  for declarations, subprocess and Docker requirements, log extracts, and audit checks.
- **No Mockito.** Use real objects, or small hand-written fakes such as `HeldRaftTransportFixture`.
- **No flaky tests.** A test that fails only sometimes is a defect. Find the race, check whether production
  code has it too, and make the test deterministic.
- **A Docker test that kills a server and starts it again waits for first snapshots first.** Call
  `DockerHealthApiHelper.awaitFirstSnapshotOnEveryServer` before the kill. A server killed while it
  writes its first snapshot restarts from its log, and one killed later restarts from its snapshot. The
  wait keeps every run on the second path, so a test does not take one or the other by timing. The first
  path has its own tests, which place the kill exactly ([TEST-RESULTS.md](TEST-RESULTS.md)).
- **A test that stops a gRPC server has no connection half made.** A call that fails before it is
  sent, such as one with a deadline already passed, still makes its channel start connecting. Stopping
  the server while it accepts that connection can log Netty's "Failed to initialize a channel". Wait
  until the channel is ready before the test can end, as `GrpcRaftServerTest.startServerAndConnect`
  does, or complete a call.
- **Raft timing uses manual timers** (`ManualRaftClusterFixture`, `ManualRaftTimersHelper`). A test fires an election or
  heartbeat itself, and doesn't wait for one on the clock.
- **Real-time waits are rare and bounded.** They belong only to the runtime end-to-end tests and the
  Docker suite, which wait out real TTLs and elections. Everywhere else, a wait's bound exists only to
  report a hang.
