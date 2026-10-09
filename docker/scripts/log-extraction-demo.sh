#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
echo "Qraft Log Extraction Pipeline Demo"
echo "Docker log path: $(docker inspect qraft-server1 --format='{{.LogPath}}' 2>/dev/null || echo unavailable)"
echo "Docker log driver: $(docker inspect qraft-server1 --format='{{.HostConfig.LogConfig}}' 2>/dev/null || echo unavailable)"

if ! "$SCRIPT_DIR/../test-data/send-heartbeat.sh" 999 demo-client http://localhost:8081 >/dev/null 2>&1; then
  registration='{"clientId":"demo-client","hostname":"demo-host","address":"127.0.0.1","port":8081,"version":"1.0.0","region":"local","datacenter":"local"}'
  curl --fail-with-body -sS -H 'Content-Type: application/json' \
    --data "$registration" \
    http://localhost:8081/api/v1/clients/register >/dev/null || true
  "$SCRIPT_DIR/../test-data/send-heartbeat.sh" 999 demo-client http://localhost:8081 >/dev/null || true
fi

docker logs qraft-server1 --tail 3 2>/dev/null || echo "No recent Docker logs available."
curl --get --fail-with-body -sS \
  --data-urlencode 'query={container_name="qraft-server1"}' \
  --data-urlencode 'limit=3' \
  http://localhost:3100/loki/api/v1/query_range || echo "Loki is not ready yet."
echo
echo "Pipeline: Java app -> Docker JSON -> Promtail -> Loki -> Grafana"
