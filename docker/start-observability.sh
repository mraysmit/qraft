#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
COMPOSE_FILE="$SCRIPT_DIR/compose/docker-compose-observability.yml"
action=${1:-up}

case "$action" in
  --down|down) docker compose -f "$COMPOSE_FILE" down -v; exit 0 ;;
  --logs|logs) docker compose -f "$COMPOSE_FILE" logs -f; exit 0 ;;
  --status|status) docker compose -f "$COMPOSE_FILE" ps; exit 0 ;;
  up) ;;
  *) echo "Usage: ./start-observability.sh [up|down|logs|status]" >&2; exit 2 ;;
esac

docker info >/dev/null 2>&1 || { echo "Docker is not running." >&2; exit 1; }
docker compose -f "$COMPOSE_FILE" up -d

waited=0
while [ "$waited" -lt 60 ]; do
  status=$(docker inspect --format='{{.State.Health.Status}}' qraft-grafana 2>/dev/null || true)
  [ "$status" = healthy ] && break
  sleep 2
  waited=$((waited + 2))
  echo "Waiting for Grafana... ($waited/60 seconds)"
done

echo "Grafana: http://localhost:3000 (admin/admin)"
echo "Prometheus: http://localhost:9090"
echo "Tempo: http://localhost:3200"
echo "Loki: http://localhost:3100"
echo "OTLP: localhost:4317 (gRPC), localhost:4318 (HTTP)"
