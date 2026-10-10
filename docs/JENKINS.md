# Qraft on Jenkins

The [Qraft job](http://192.168.137.32:8080/job/Qraft/) runs every test suite. Select **Build Now**
to run the pipeline; it has no recurring trigger.

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

Every Maven command uses the job's Maven repository cache, streams combined output to the Jenkins
console and a timestamped file under `logs/`, and preserves Maven's exit status through `tee`.
Builds and suites run sequentially. A failed default build stops the dependent suites; an end-to-end
failure still allows Docker tests to run. The build timeout is one hour.

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
[the single-POM task list](../docs-design/task-list-single-pom-and-quorus-removal-2026-10-10.md).
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
