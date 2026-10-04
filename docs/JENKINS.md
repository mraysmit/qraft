# Qraft on Jenkins

The [Qraft job](http://192.168.137.32:8080/job/Qraft/) runs every test suite. Select **Build Now**
to run the pipeline; it has no recurring trigger.

## Job configuration

The job loads its pipeline and the Linux fixture permission fix from the published CI branch using
the following SCM configuration. The initial verification builds used an inline pipeline; build 2
retains that run's fixture patch as `ci-fixture.patch` in its artifacts.

- Type: Pipeline.
- Definition: Pipeline script from SCM.
- SCM: Git, repository `https://github.com/mraysmit/qraft.git`.
- Branch: `*/ci/jenkins-all-tests` while the CI changes are under review; change to `*/main` after merging.
- Script path: `Jenkinsfile`.
- Lightweight checkout: enabled.

The pipeline checks out the same SCM revision that supplied its Jenkinsfile. It needs an online Linux
x64 node labelled `linux`, Bash, Git, curl, Maven 3.9 or newer, and access to a running Docker engine
with Docker Compose. The current server provides these tools. No Jenkins plugin installation is needed.

The first build downloads SapMachine JDK 27 from its official GitHub release and verifies its pinned
SHA-256 checksum. The JDK is cached in `<workspace>@tools/sapmachine-jdk-27`; Maven dependencies are
cached in `<workspace>@repository`. These directories survive fresh source checkouts. The pipeline
sets its own Java environment without changing other jobs' Java installations.

## Suites and results

1. **Default tests and coverage gates:** `mvn -B --fail-at-end -Dstyle.color=never clean install`
   across the complete reactor. This also builds the executable runtime jar needed by Docker tests.
2. **End-to-end tests:** `mvn -B -Dstyle.color=never test -Dgroups=e2e -Dtest.excludedGroups=`.
3. **Docker cluster tests:** `mvn -B -Dstyle.color=never test '-Dgroups=docker|slow' -Dtest.excludedGroups=`.
   Including `slow` also covers that controller group if tests are tagged with it later.

Every Maven command uses the job's Maven repository cache, streams combined output to the Jenkins
console and a timestamped file under `logs/`, and preserves Maven's exit status through `tee`.
Builds and suites run sequentially. A failed reactor build stops the dependent suites; an end-to-end
failure still allows Docker tests to run. The build timeout is one hour.

JUnit results appear under **Test Result**. Build artifacts contain the logs, complete Surefire reports
under `reports/default`, `reports/e2e`, and `reports/docker`, and the default build's JaCoCo reports under
`coverage/`. JVM fatal-error and replay logs are also retained. Reports are copied before a later suite
can overwrite them. Jenkins retains 20 builds and artifacts from the latest 10 builds.

## Linux container test fixtures

Qraft containers run as UID 1001. Java temporary files on Linux initially allow only their owner to read
them, so a configuration created by the Jenkins user cannot be read by a bind-mounted container.
`SharedDockerCluster` grants read permission to the image's user for its non-secret agent and storage-lock
test configurations on POSIX filesystems. The image continues to run with its normal user, and the mounts
remain read-only. The existing Docker topology and storage-lock tests exercise these configurations.

## Initial verification, 2026-10-03

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

## Memory corruption investigation, 2026-10-03

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

Further diagnosis needs access to the machine hosting this VM: review its VMware and hardware
error logs and test the host's memory. Recheck memory integrity after resolving the host/VM fault,
then rerun Qraft's full pipeline to verify the Linux fixture fix and all suites. The published
pipeline remains available, but successful execution of every suite is still outstanding.
