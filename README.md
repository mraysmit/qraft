# Qraft

Qraft is a Java-based distributed systems framework focused on Raft consensus and transfer-free distributed state primitives.

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
