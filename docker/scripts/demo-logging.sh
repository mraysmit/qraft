#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
echo "Qraft Log Aggregation Demo"
docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}' --filter name=qraft

curl --fail-with-body -sS -H 'Content-Type: application/json' \
  --data-binary "@$SCRIPT_DIR/../test-data/test-registration.json" \
  http://localhost:8080/api/v1/agents/register >/dev/null || true

i=1
while [ "$i" -le 3 ]; do
  "$SCRIPT_DIR/../test-data/send-heartbeat.sh" "$i" test-agent-002 >/dev/null
  echo "Heartbeat $i acknowledged"
  sleep 1
  i=$((i + 1))
done

echo "Grafana: http://localhost:3000 (admin/admin)"
echo "Loki: http://localhost:3100"
echo "Prometheus: http://localhost:9090"
curl --get --fail-with-body -sS \
  --data-urlencode 'query={container_name="qraft-controller1"}' \
  --data-urlencode 'limit=5' \
  http://localhost:3100/loki/api/v1/query_range || echo "Loki is not ready yet."
echo
