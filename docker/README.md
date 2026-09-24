# Qraft Docker infrastructure

This directory contains Docker Compose environments for Qraft controller
clusters and observability.

## Controller clusters

Run commands from this directory. The helper script exposes the maintained
development entry points:

```powershell
.\start.ps1 cluster       # single-controller development environment
.\start.ps1 controllers   # three controllers behind a load balancer
.\start.ps1 status
.\start.ps1 stop
```

POSIX shell equivalents are provided for Linux, macOS, and WSL:

```sh
sh ./start.sh cluster
sh ./start.sh controllers
sh ./start.sh status
sh ./start.sh stop
```

The compose directory also contains dedicated three-node, five-node, and
network-partition configurations used for distributed testing. These require
the unified `qraft-runtime` image. Qraft does not use environment variables for
runtime configuration. Compose mounts a versioned JSON file for each process and
starts it with `server --config /etc/qraft/server.json` or
`client --config /etc/qraft/client.json`. The configuration path is always an
explicit command argument, not an environment variable.

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

Controller HTTP endpoints are exposed on ports 8080 or 8081-8085, depending
on the selected topology. Check a running endpoint with:

```powershell
Invoke-RestMethod http://localhost:8080/health
```

## Agent API checks

With a controller running, `start-quick.ps1 test` uses the JSON payloads and
PowerShell scripts under `test-data/` to exercise agent registration,
heartbeat, and listing endpoints.

```powershell
.\start-quick.ps1 test
```

```sh
sh ./start-quick.sh test
```

## Observability

The maintained observability stack contains OpenTelemetry Collector, Tempo,
Prometheus, Loki, and Grafana:

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

## Persistent state

Compose environments use named volumes for controller and observability data.
Use `docker compose down` to retain them or `docker compose down -v` when an
explicit clean reset is required.
