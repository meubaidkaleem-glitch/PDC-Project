#!/usr/bin/env bash
# Smoke test of the Linux standalone package (docs/standalone.md): unpacks it into a folder whose name has a space,
# starts the launcher, signs in with the initial password it prints, checks the two local workers, runs a
# distributed experiment to a verified result, checks that a second start only reports the running instance and
# that the data survives a restart. The Windows package has the same test in CI (.github/workflows/standalone.yml).
#   scripts/standalone-smoke.sh dist/DistriCompute-<version>-linux-x64.tar.gz
# With STANDALONE_SMOKE_IMAGE=ubuntu:24.04 the launcher runs in that container image, which has no Java, using the
# host's network; otherwise it runs here. STANDALONE_SMOKE_KEEP=1 keeps the unpacked package and the console logs.
# Needs curl and python3.
set -euo pipefail
ARCHIVE="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
IMAGE="${STANDALONE_SMOKE_IMAGE:-}"
PORT="${STANDALONE_SMOKE_PORT:-18080}"
BASE="http://127.0.0.1:$PORT"
WORK="$(mktemp -d)"
DIR="$WORK/Distri Compute"
DATA="$WORK/my data"
LOG="$WORK/console-1.log"
JAR="$WORK/cookies"
CONTAINER="districompute-smoke-$$"
mkdir -p "$DIR" "$DATA"
tar -xzf "$ARCHIVE" -C "$DIR"
LAUNCHER="$(ls "$DIR"/*/districompute.sh)"

start_app() {
  if [ -n "$IMAGE" ]; then
    # As the calling user, so that the files it writes can be deleted afterwards.
    docker run --rm --init --name "$CONTAINER" --network host -v "$WORK:$WORK" --user "$(id -u):$(id -g)" \
      -e HOME="$WORK" -e DISTRICOMPUTE_DATA_DIR="$DATA" -e DISTRICOMPUTE_HTTP_PORT="$PORT" "$IMAGE" \
      "$LAUNCHER" --districompute.standalone.open-browser=false > "$LOG" 2>&1 &
  else
    env -u JAVA_HOME DISTRICOMPUTE_DATA_DIR="$DATA" DISTRICOMPUTE_HTTP_PORT="$PORT" \
      "$LAUNCHER" --districompute.standalone.open-browser=false > "$LOG" 2>&1 &
  fi
  APP_PID=$!
  for _ in $(seq 1 180); do
    if grep -q "DistriCompute is running at" "$LOG" 2>/dev/null; then return 0; fi
    if ! kill -0 "$APP_PID" 2>/dev/null; then cat "$LOG"; echo "FAIL: the application exited" >&2; exit 1; fi
    sleep 1
  done
  cat "$LOG"; echo "FAIL: not ready after 180 s" >&2; exit 1
}

stop_app() {
  if [ -n "$IMAGE" ]; then docker stop -t 30 "$CONTAINER" > /dev/null; else kill "$APP_PID"; fi
  wait "$APP_PID" 2>/dev/null || true
}

cleanup() {
  if [ -n "$IMAGE" ]; then docker rm -f "$CONTAINER" > /dev/null 2>&1 || true; fi
  if [ -n "${APP_PID:-}" ]; then kill "$APP_PID" 2>/dev/null || true; fi
  if [ -n "${STANDALONE_SMOKE_KEEP:-}" ]; then echo "Kept $WORK"; else rm -rf "$WORK" || true; fi
}
trap cleanup EXIT

xsrf() { awk '$6 == "XSRF-TOKEN" { print $7 }' "$JAR"; }
api() { # method path [json]
  curl -sf -b "$JAR" -c "$JAR" -X "$1" -H "X-XSRF-TOKEN: $(xsrf)" -H 'Content-Type: application/json' \
    ${3:+--data "$3"} "$BASE$2"
}
json() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }

echo "== First start"
start_app
grep -q "DistriCompute is running at http://localhost:$PORT/" "$LOG" || { cat "$LOG"; echo "FAIL: wrong address" >&2; exit 1; }
PASSWORD="$(sed -n 's/.*First sign-in:  user admin   password \([^ ]*\).*/\1/p' "$LOG")"
[ -n "$PASSWORD" ] || { cat "$LOG"; echo "FAIL: no initial password printed" >&2; exit 1; }

curl -sf -c "$JAR" "$BASE/api/v1/auth/session" > /dev/null
curl -sf -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(xsrf)" --data-urlencode username=admin \
  --data-urlencode "password=$PASSWORD" "$BASE/api/v1/auth/login" > /dev/null
echo "signed in"

ACTIVE="$(api GET /api/v1/workers | json "sum(1 for w in d if w['status'] == 'ACTIVE' and w['workerId'].startswith('local-'))")"
[ "$ACTIVE" = 2 ] || { echo "FAIL: $ACTIVE active local workers" >&2; exit 1; }
echo "2 local workers active"

ID="$(api POST /api/v1/experiments '{"name":"smoke","task":{"algorithm":"matrix.multiply","parameters":{"rowsA":128,"colsA":96,"colsB":64,"seed":3},"execution":{"mode":"DISTRIBUTED_CPU"}}}' | json "d['summary']['id']")"
for _ in $(seq 1 120); do
  STATUS="$(api GET "/api/v1/experiments/$ID" | json "d['summary']['status']")"
  case "$STATUS" in COMPLETED|FAILED|CANCELLED) break ;; esac
  sleep 1
done
[ "$STATUS" = COMPLETED ] || { echo "FAIL: experiment $STATUS" >&2; exit 1; }
VERIFIED="$(api GET "/api/v1/experiments/$ID" | json "d['runs'][0]['verificationPassed']")"
[ "$VERIFIED" = True ] || { echo "FAIL: verification $VERIFIED" >&2; exit 1; }
echo "distributed experiment completed and verified"

echo "== Second start while running"
SECOND="$(env DISTRICOMPUTE_HTTP_PORT="$PORT" DISTRICOMPUTE_DATA_DIR="$DATA" "$LAUNCHER")"
echo "$SECOND" | grep -q "already running" || { echo "FAIL: second start: $SECOND" >&2; exit 1; }
echo "reported the running instance"

echo "== Restart"
stop_app
LOG="$WORK/console-2.log"
start_app
rm -f "$JAR"
curl -sf -c "$JAR" "$BASE/api/v1/auth/session" > /dev/null
curl -sf -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(xsrf)" --data-urlencode username=admin \
  --data-urlencode "password=$PASSWORD" "$BASE/api/v1/auth/login" > /dev/null
KEPT="$(api GET "/api/v1/experiments/$ID" | json "d['summary']['status']")"
[ "$KEPT" = COMPLETED ] || { echo "FAIL: experiment after restart: $KEPT" >&2; exit 1; }
echo "data kept across the restart"
stop_app
if grep -E ' ERROR |Exception' "$WORK"/console*.log; then echo "FAIL: errors in the console" >&2; exit 1; fi
echo "PASS"
