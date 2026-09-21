# Qraft

Qraft is a Java 25 service-discovery and distributed-coordination platform built on Raft consensus.

It is designed for systems that need strongly ordered commands, replicated state, service discovery, health reporting, agent coordination, and tenant isolation across a cluster.

## Core Model

- Controllers accept commands and replicate them through the Raft log
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

## Modules

- `qraft-raft-engine`: implementation-neutral Raft contracts and primitives
- `qraft-distributed-state`: deterministic replicated-state commands and projections
- `qraft-core`: shared service-discovery, health, node, and agent domain types
- `qraft-agent`: client-mode registration, heartbeat, and local health behavior
- `qraft-tenant`: namespace and tenant management
- `qraft-controller`: Raft coordination, replicated state, and control-plane APIs
- `qraft-runtime`: the executable composition root for `server` and `client` modes

## Quick Start

```bash
# Build and test the complete reactor
mvn clean test
```

## Local Cluster and Observability

Docker Compose environments for clustered runs, networking tests, and observability are under `docker/`.

See `docker/README.md` for available topologies and startup scripts.
