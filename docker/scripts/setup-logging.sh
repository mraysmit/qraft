#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
mkdir -p "$SCRIPT_DIR/../logging/loki" \
  "$SCRIPT_DIR/../logging/promtail" \
  "$SCRIPT_DIR/../logging/grafana/provisioning/datasources" \
  "$SCRIPT_DIR/../logging/grafana/provisioning/dashboards" \
  "$SCRIPT_DIR/../logging/prometheus"

cat >"$SCRIPT_DIR/../logging/prometheus/prometheus.yml" <<'EOF'
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: prometheus
    static_configs:
      - targets: ['localhost:9090']
  - job_name: qraft-controllers
    static_configs:
      - targets: ['controller1:8080', 'controller2:8080', 'controller3:8080']
    metrics_path: /metrics
    scrape_interval: 5s
EOF

cat >"$SCRIPT_DIR/../logging/grafana/provisioning/dashboards/dashboards.yml" <<'EOF'
apiVersion: 1
providers:
  - name: default
    orgId: 1
    folder: ''
    type: file
    disableDeletion: false
    updateIntervalSeconds: 10
    allowUiUpdates: true
    options:
      path: /etc/grafana/provisioning/dashboards
EOF

docker compose -f "$SCRIPT_DIR/../compose/docker-compose-loki.yml" up -d
echo "Grafana: http://localhost:3000 (admin/admin)"
echo "Loki: http://localhost:3100"
echo "Prometheus: http://localhost:9090"
