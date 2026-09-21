#!/bin/bash

# Qraft Agent Docker Entry Point
# This script configures and starts the Qraft Agent

set -e

echo "Starting Qraft Agent..."
echo "Agent ID: ${AGENT_ID:-not-set}"
echo "Region: ${AGENT_REGION:-default}"
echo "Datacenter: ${AGENT_DATACENTER:-default}"
echo "Controller URL: ${CONTROLLER_URL:-http://localhost:8080/api/v1}"

# Validate required environment variables
if [ -z "$AGENT_ID" ]; then
    echo "ERROR: AGENT_ID environment variable is required"
    exit 1
fi

if [ -z "$CONTROLLER_URL" ]; then
    echo "ERROR: CONTROLLER_URL environment variable is required"
    exit 1
fi

# Set default values for optional variables
export AGENT_REGION=${AGENT_REGION:-default}
export AGENT_DATACENTER=${AGENT_DATACENTER:-default}
export SUPPORTED_PROTOCOLS=${SUPPORTED_PROTOCOLS:-HTTP,HTTPS}
export HEARTBEAT_INTERVAL=${HEARTBEAT_INTERVAL:-30000}
export AGENT_PORT=${AGENT_PORT:-8080}
export AGENT_VERSION=${AGENT_VERSION:-1.0.0}

# Configure Java options
JAVA_OPTS="${JAVA_OPTS:-} -Dqraft.agent.id=$AGENT_ID"
JAVA_OPTS="$JAVA_OPTS -Dqraft.agent.region=$AGENT_REGION"
JAVA_OPTS="$JAVA_OPTS -Dqraft.agent.datacenter=$AGENT_DATACENTER"
JAVA_OPTS="$JAVA_OPTS -Dqraft.controller.url=$CONTROLLER_URL"

# JVM tuning for containers
JAVA_OPTS="$JAVA_OPTS -XX:+UseContainerSupport"
JAVA_OPTS="$JAVA_OPTS -XX:MaxRAMPercentage=75.0"
JAVA_OPTS="$JAVA_OPTS -XX:+UseG1GC"
JAVA_OPTS="$JAVA_OPTS -XX:+UseStringDeduplication"

# Logging configuration
JAVA_OPTS="$JAVA_OPTS -Dlogback.configurationFile=/app/config/logback.xml"

export JAVA_OPTS

echo "Java Options: $JAVA_OPTS"
echo "Supported Protocols: $SUPPORTED_PROTOCOLS"
echo "Heartbeat Interval: ${HEARTBEAT_INTERVAL}ms"

# Wait for controller to be available
controller_base=${CONTROLLER_URL%/}
controller_health_url=${CONTROLLER_HEALTH_URL:-${controller_base%/api/v1}/health}
echo "Controller health URL: $controller_health_url"
echo "Waiting for controller to be available..."
timeout=60
counter=0
while ! curl -f "$controller_health_url" >/dev/null 2>&1; do
    if [ $counter -ge $timeout ]; then
        echo "ERROR: Controller not available after ${timeout} seconds"
        exit 1
    fi
    echo "Controller not ready, waiting... ($counter/$timeout)"
    sleep 1
    counter=$((counter + 1))
done

echo "Controller is available, starting agent..."

# Start the agent
exec java $JAVA_OPTS -jar app.jar
