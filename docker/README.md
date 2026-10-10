# Qraft Docker infrastructure

This directory contains Docker Compose environments for Qraft server
clusters and observability.

## Server clusters

Run commands from this directory. The helper script exposes the maintained
development entry points:

```powershell
.\start.ps1 cluster       # one server with a client, for development
.\start.ps1 multinode     # three servers
.\start.ps1 status
.\start.ps1 stop
```

POSIX shell equivalents are provided for Linux, macOS, and WSL:

```sh
sh ./start.sh cluster
sh ./start.sh multinode
sh ./start.sh status
sh ./start.sh stop
```

`start-quick.ps1 cluster [3node|5node|network-test]` and its shell equivalent
start the three-server, five-server, and network-partition clusters. These require
the unified `qraft-runtime` image. Qraft does not use environment variables for
runtime configuration. Compose mounts a versioned JSON file for each process and
starts servers with `server --config /etc/qraft/server.json`. The client image uses
`client` and discovers its mounted `/etc/qraft/client.json` through the standard
location. Operators may instead use `-Dqraft.config=<path>` or the local
`config/<role>.json` convention. No discovery method reads an environment variable.

## Client configuration

[`config/client.json`](config/client.json) is a complete client example. It
declares the client, server origins, reconciliation policy, and services in one
versioned file; it contains no environment-variable placeholders. Server URLs
must be origins such as `http://server:8080`, without an API path, query, or
fragment.

Mount the file read-only and select it explicitly when the container may use an
arbitrary destination:

```yaml
services:
  client:
    build:
      context: ..
      dockerfile: docker/Dockerfile
    command: ["client", "--config", "/etc/qraft/example-client.json"]
    volumes:
      - ./config/client.json:/etc/qraft/example-client.json:ro
```

When mounted at the conventional system path, the path argument is unnecessary:

```yaml
services:
  client:
    build:
      context: ..
      dockerfile: docker/Dockerfile
    command: ["client"]
    volumes:
      - ./config/client.json:/etc/qraft/client.json:ro
```

Outside the container entrypoint, the JVM locator is the third supported method:

```text
java -Dqraft.config=/opt/qraft/client.json -jar qraft.jar client
```

The `--config` argument has highest precedence, followed by the `qraft.config` JVM
property, `config/client.json` relative to the working directory, and finally
`/etc/qraft/client.json`.

Compose packages the runtime JAR built on the host; it does not run Maven inside
Docker. The `start.ps1`, `start.sh`, `start-quick.ps1`, and `start-quick.sh`
helpers run the local build automatically before starting a cluster. To build
the artifact without starting Docker, run either helper:

```powershell
.\build-runtime.ps1
```

```sh
sh ./build-runtime.sh
```

Server HTTP endpoints are exposed on ports 8080 or 8081-8085, depending
on the selected topology. Check a running endpoint with:

```powershell
Invoke-RestMethod http://localhost:8080/health/ready
```

## Observability

There is one observability stack: OpenTelemetry Collector, Tempo, Prometheus,
Loki, and Grafana:

```powershell
.\start-observability.ps1
.\start-observability.ps1 -Status
.\start-observability.ps1 -Down
```

```sh
sh ./start-observability.sh
sh ./start-observability.sh status
sh ./start-observability.sh down
```

Default local endpoints:

| Service | Endpoint |
| --- | --- |
| Grafana | http://localhost:3000 |
| Prometheus | http://localhost:9090 |
| Tempo | http://localhost:3200 |
| Loki | http://localhost:3100 |
| OTLP gRPC | localhost:4317 |
| OTLP HTTP | localhost:4318 |

The default Grafana development credentials are `admin` / `admin`.

The scripts start the stack alone, for servers run on this machine. A second
compose file starts the same stack with three servers that report to it. Both
use the same container names and ports, so stop one before starting the other:

```powershell
.\build-runtime.ps1
docker compose -f compose/docker-compose-observability-cluster.yml up -d
```

## Persistent state

Compose environments use named volumes for server and observability data.
Use `docker compose down` to retain them or `docker compose down -v` when an
explicit clean reset is required.
