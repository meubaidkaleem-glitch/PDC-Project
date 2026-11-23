#!/usr/bin/env bash
# Starts a coordinator from a properties file (docs/deployment.md).
#   scripts/start-coordinator.sh [config.properties] [more options...]
# DISTRICOMPUTE_JAR selects the runnable jar; JAVA_OPTS adds JVM options (for example -Xmx4g).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
JAR="${DISTRICOMPUTE_JAR:-$HERE/../target/districompute-distributed-0.1.0-SNAPSHOT-all.jar}"
CONFIG="${1:-$HERE/../examples/config/coordinator.properties}"
[ $# -gt 0 ] && shift
if [ ! -f "$JAR" ]; then
  echo "Runnable jar not found: $JAR. Build it (see README.md) or set DISTRICOMPUTE_JAR." >&2
  exit 1
fi
# JAVA_OPTS is split into words on purpose.
# shellcheck disable=SC2086
exec java ${JAVA_OPTS:-} -jar "$JAR" coordinator --config "$CONFIG" "$@"
