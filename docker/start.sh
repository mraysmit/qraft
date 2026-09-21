#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$SCRIPT_DIR"

service=${1:-help}
case "$service" in
  cluster)
    "$SCRIPT_DIR/build-runtime.sh"
    echo "Starting Qraft single-controller development environment..."
    docker compose -f compose/docker-compose-single-controller.yml up -d
    echo "Controller with embedded HTTP API available at http://localhost:8080"
    ;;
  multinode)
    "$SCRIPT_DIR/build-runtime.sh"
    echo "Starting Qraft multi-node cluster..."
    docker compose -f compose/docker-compose-cluster.yml up -d
    echo "Controllers available at http://localhost:8081, :8082, and :8083"
    ;;
  controllers)
    "$SCRIPT_DIR/build-runtime.sh"
    echo "Starting Qraft controller-first cluster..."
    docker compose -f compose/docker-compose-controller-first.yml up -d
    echo "Load-balanced API available at http://localhost:8080"
    ;;
  logging)
    docker compose -f compose/docker-compose-loki.yml up -d
    echo "Grafana available at http://localhost:3000 (admin/admin)"
    ;;
  stop)
    for file in docker-compose-controller-first.yml docker-compose-cluster.yml docker-compose.yml docker-compose-loki.yml; do
      docker compose -f "compose/$file" down >/dev/null 2>&1 || true
    done
    echo "Services stopped."
    ;;
  status)
    docker ps --filter name=qraft- --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
    ;;
  *)
    echo "Usage: ./start.sh {cluster|multinode|controllers|logging|stop|status}"
    ;;
esac
