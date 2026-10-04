#!/bin/sh
set -eu

exec java -Xmx512m -Xms128m -jar /app/qraft.jar "$@"
