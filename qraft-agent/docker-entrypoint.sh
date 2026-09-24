#!/bin/sh
set -eu

exec java -Xmx256m -Xms128m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 \
    -XX:+UseG1GC -XX:+UseStringDeduplication -jar /app/app.jar "$@"
