# Qraft

Qraft is a Java 27 service-discovery and distributed-coordination platform built on Raft consensus.

It is designed for systems that need strongly ordered commands, replicated state, service discovery, health reporting, agent coordination, and tenant isolation across a cluster.

## Core Model

- Servers accept commands and replicate them through the Raft log
- Commands are applied deterministically to maintain consistent cluster state
- Agents register with the control plane and report health
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
- Shared types: shared service-discovery, health, node, and agent domain types
- Client: client-mode registration, heartbeat, and local health behavior
- Server: Raft coordination, replicated state, and control-plane APIs
- Entry point: the executable composition root for `server` and `client` modes

The packages of each layer are listed in
[the design document](docs-design/QRAFT_DISTRIBUTED_SERVICE_PLATFORM_DESIGN.md), section 1.1.

## Quick Start

```bash
# Build and test, with the coverage gates
mvn clean install
```

See [docs/TESTING.md](docs/TESTING.md) for the test suites, running a single test class, and the Docker suite.

## Local Cluster and Observability

Docker Compose environments for clustered runs, networking tests, and observability are under `docker/`.

See `docker/README.md` for available topologies and startup scripts.
