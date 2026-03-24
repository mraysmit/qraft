# Qraft

Qraft is a Java-based distributed control plane built on Raft consensus.

It is designed for systems that need strongly ordered commands, replicated state, agent coordination, workflow execution, and tenant isolation across a cluster.

## Core Model

- Controllers accept commands and replicate them through the Raft log
- Commands are applied deterministically to maintain consistent cluster state
- Agents register with the control plane, report health, and execute assigned work
- Tenants provide logical isolation for policies, workflows, and state
- Workflows coordinate multi-step operations on top of the replicated state model

## Good Fit

Qraft is a good fit for:

- Distributed job orchestration
- Multi-tenant workflow control planes
- Configuration and policy management
- Coordination services that need durable ordered state changes

## Modules

- `qraft-raft-engine`: core Raft consensus implementation
- `qraft-distributed-state`: distributed state primitives built on Raft
- `qraft-core` and `qraft-integration-examples`: optional profile modules for integration scenarios

## Quick Start

```bash
# Build core modules
mvn clean test

# Build integration examples too
mvn -P integration-examples clean test
```

## Local Cluster and Observability

Docker Compose environments for clustered runs, networking tests, and observability are under `docker/`.

See `docker/README.md` for available topologies and startup scripts.
