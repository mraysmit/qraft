# Qraft

Qraft is a Java-based distributed systems framework focused on Raft consensus and transfer-free distributed state primitives.

## What "Transfer-Free" Means

In Qraft, nodes do not copy full datasets or payloads between each other during normal operation.

- Each node maintains local state
- The cluster replicates ordered commands/state changes, not full data transfers
- Nodes apply the same ordered updates to converge on consistent state
- Reads are served from local materialized state

"Transfer-free" does not mean zero network traffic. It means the system avoids bulk state shipping as its primary consistency mechanism.

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
