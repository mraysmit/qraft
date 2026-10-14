# Qraft

Qraft is a Java 27 service-discovery and distributed-coordination platform built on Raft consensus.

It is designed for systems that need strongly ordered commands, replicated state, service discovery, health reporting, client coordination, and tenant isolation across a cluster.

## Core Model

- Servers accept commands and replicate them through the Raft log
- Commands are applied deterministically to maintain consistent cluster state
- Clients register with the control plane and report health
- Tenants and namespaces provide logical isolation for policies and state

## Good Fit

Qraft is a good fit for:

- Service discovery and health monitoring
- Distributed key/value state, sessions, and locks
- Configuration and policy management
- Coordination services that need durable ordered state changes

### Architecture cleanup note

- Qraft persistence defaults to `raftlog-core` as the production WAL-backed Raft storage implementation.

## Structure

Qraft is one Maven project that builds one executable jar, `target/qraft.jar`.
Its code is divided into layers of packages, which a dependency test keeps
apart:

- Raft: consensus implementation, contracts, transport, storage adapters, and metrics
- Replicated state: deterministic replicated-state commands and projections
- Common: shared domain types, configuration helpers, deadlines, caller context, and executors
- Client: client-mode registration, heartbeat, and local health behavior
- Server: Raft coordination, replicated state, and control-plane APIs
- Entry point: the executable composition root for `server` and `client` modes

The packages of each layer are listed in
[the design document](docs-design/QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md), section 1.1.

The server owns a dedicated Raft state loop and a separate bounded executor for
blocking work on virtual threads. Both preserve MDC and OpenTelemetry caller
context. Shutdown stops the service, drains blocking work, stops the state loop,
and closes telemetry. Asynchronous contracts use Java's `CompletableFuture` and
`CompletionStage`; see the design's section 17 for ownership and completion rules.

## Quick Start

Run from the repository root in the visible VS Code integrated terminal with
PowerShell 7:

```powershell
New-Item -ItemType Directory -Force .\logs | Out-Null
mvn -B "-Dstyle.color=never" clean install 2>&1 | Tee-Object -FilePath ".\logs\qraft-tests-$(Get-Date -Format 'yyyy-MM-dd_HH-mm-ss-fff').log"
$qraftBuildExitCode = $LASTEXITCODE
if ($qraftBuildExitCode -ne 0) { throw "Maven failed with exit code $qraftBuildExitCode" }
```

See [docs/TESTING.md](docs/TESTING.md) for the test suites, running a single test class, and the Docker suite.
Mutation testing with PIT is configured but on ice since 2026-10-11, at the owner's decision: see
[its section there](docs/TESTING.md#mutation-testing-with-pit).

## Local Cluster and Observability

Docker Compose environments for clustered runs, networking tests, and observability are under `docker/`.

See [docker/README.md](docker/README.md) for available topologies and startup scripts,
and [the storage runbook](docs/RAFT_STORAGE_OPERATIONS.md#detecting-corruption-and-fencing)
for fenced-node diagnostics and recovery.
