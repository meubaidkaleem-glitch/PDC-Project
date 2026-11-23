#!/usr/bin/env bash
# Local multi-worker demonstration: a coordinator and several independent worker processes on this machine,
# connected over loopback TCP. It asks for advice, runs example tasks, prints the cluster status, and stops
# everything (workers first, gracefully).
#   scripts/local-demo.sh [workers] [task.json ...]
# Environment: SLOTS (attempts per worker, default 1), PORT (default 17650), HTTP_PORT (default 18650),
# POLICY (default dynamic), DISTRICOMPUTE_JAR.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
JAR="${DISTRICOMPUTE_JAR:-$HERE/../target/districompute-distributed-0.1.0-SNAPSHOT-all.jar}"
WORKERS="${1:-3}"
[ $# -gt 0 ] && shift
TASKS=("$@")
if [ ${#TASKS[@]} -eq 0 ]; then
  TASKS=("$HERE/../examples/tasks/primes-distributed.json" "$HERE/../examples/tasks/matmul-distributed.json"
         "$HERE/../examples/tasks/sort-distributed.json" "$HERE/../examples/tasks/primes-sequential.json")
fi
SLOTS="${SLOTS:-1}"
PORT="${PORT:-17650}"
HTTP_PORT="${HTTP_PORT:-18650}"
POLICY="${POLICY:-dynamic}"
if [ ! -f "$JAR" ]; then
  echo "Runnable jar not found: $JAR. Build it (see README.md) or set DISTRICOMPUTE_JAR." >&2
  exit 1
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/districompute-demo.XXXXXX")"
API="http://127.0.0.1:$HTTP_PORT"
COORDINATOR_PID=""
WORKER_PIDS=()
cleanup() {
  echo "Stopping the workers (SIGTERM: they drain and disconnect), then the coordinator."
  if [ ${#WORKER_PIDS[@]} -gt 0 ]; then
    kill "${WORKER_PIDS[@]}" 2> /dev/null || true
    wait "${WORKER_PIDS[@]}" 2> /dev/null || true
  fi
  if [ -n "$COORDINATOR_PID" ]; then
    kill "$COORDINATOR_PID" 2> /dev/null || true
    wait "$COORDINATOR_PID" 2> /dev/null || true
  fi
  rm -f "$WORK/cluster.secret" "$WORK/api.token"
  echo "Logs: $WORK"
}
trap cleanup EXIT

# A fresh secret and API token for this demonstration only, readable only by this user.
umask 077
head -c 32 /dev/urandom | base64 > "$WORK/cluster.secret"
head -c 24 /dev/urandom | base64 > "$WORK/api.token"
cli() { java -jar "$JAR" "$@"; }

echo "Starting the coordinator on 127.0.0.1:$PORT (HTTP API on $API, policy $POLICY)"
# java is started directly (not through the cli function), so that $! is the JVM's process id and the cleanup
# stops the JVM itself rather than a subshell.
java -jar "$JAR" coordinator --bind 127.0.0.1 --port "$PORT" --secret-file "$WORK/cluster.secret" \
  --policy "$POLICY" --http-port "$HTTP_PORT" --http-token-file "$WORK/api.token" > "$WORK/coordinator.log" 2>&1 &
COORDINATOR_PID=$!

echo "Starting $WORKERS worker processes with $SLOTS slot(s) each"
for i in $(seq 1 "$WORKERS"); do
  java -jar "$JAR" worker --coordinator "127.0.0.1:$PORT" --id "demo-w$i" --max-concurrent-tasks "$SLOTS" \
    --secret-file "$WORK/cluster.secret" > "$WORK/worker-$i.log" 2>&1 &
  WORKER_PIDS+=($!)
done

echo "Waiting for $WORKERS workers to register"
for _ in $(seq 1 60); do
  ACTIVE=$(cli status --api "$API" --token-file "$WORK/api.token" 2> /dev/null | grep -c " ACTIVE " || true)
  [ "$ACTIVE" -ge "$WORKERS" ] && break
  sleep 1
done
if [ "${ACTIVE:-0}" -lt "$WORKERS" ]; then
  echo "Only ${ACTIVE:-0} of $WORKERS workers registered; see the logs in $WORK" >&2
  exit 1
fi

STATUS=0
for task in "${TASKS[@]}"; do
  echo
  echo "=== $(basename "$task") ==="
  cli advise "$task" --api "$API" --token-file "$WORK/api.token"
  cli submit "$task" --api "$API" --token-file "$WORK/api.token" --wait \
    --override-reason "local demonstration" || STATUS=1
done
echo
cli status --api "$API" --token-file "$WORK/api.token"
exit $STATUS
