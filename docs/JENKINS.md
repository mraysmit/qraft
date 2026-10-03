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
2. **End-to-end tests:** `mvn -B -Dstyle.color=never test -pl qraft-runtime -Dgroups=e2e -Dtest.excludedGroups=`.
3. **Docker cluster tests:** `mvn -B -Dstyle.color=never test -pl qraft-controller '-Dgroups=docker|slow' -Dtest.excludedGroups=`.
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
