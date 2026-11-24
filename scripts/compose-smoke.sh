#!/usr/bin/env bash
# Smoke test of the Docker Compose deployment (docs/deployment.md): builds the images from the jars of
# scripts/build.sh, starts MySQL, the application and two workers with generated passwords, checks that the
# application is healthy, that both workers registered and that a distributed experiment completes with a verified
# result, then removes everything (containers and volumes).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

WORK=$(mktemp -d "${TMPDIR:-/tmp}/districompute-compose.XXXXXX")
ENV_FILE="$WORK/compose.env"
ADMIN_PASSWORD="smoke-$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')"
printf 'DISTRICOMPUTE_DB_PASSWORD=%s\nDISTRICOMPUTE_ADMIN_PASSWORD=%s\n' \
  "$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')" "$ADMIN_PASSWORD" > "$ENV_FILE"
PROJECT="districompute-smoke-$$"
compose() { docker compose -p "$PROJECT" --env-file "$ENV_FILE" "$@"; }
cleanup() {
  compose logs --no-color > "$WORK/compose.log" 2>&1 || true
  compose down -v > /dev/null 2>&1 || true
  echo "Compose logs: $WORK/compose.log"
}
trap cleanup EXIT

compose build
compose up -d
echo "Waiting for the application to be healthy..."
for _ in $(seq 1 90); do
  if [[ "$(docker inspect -f '{{.State.Health.Status}}' "$PROJECT-app-1" 2>/dev/null)" == healthy ]]; then break; fi
  sleep 2
done
[[ "$(docker inspect -f '{{.State.Health.Status}}' "$PROJECT-app-1")" == healthy ]] || { echo "The application is not healthy" >&2; exit 1; }
for _ in $(seq 1 60); do
  if [[ $(compose logs worker 2>/dev/null | grep -c 'registered with') -ge 2 ]]; then break; fi
  sleep 2
done
[[ $(compose logs worker | grep -c 'registered with') -ge 2 ]] || { echo "The workers did not register" >&2; exit 1; }

# Sign in and run a distributed experiment through the API, as the interface does (session cookie, CSRF header).
JAR="$WORK/cookies"
BASE=http://127.0.0.1:8080
token() { awk '/XSRF-TOKEN/ {print $7}' "$JAR"; }
curl -fsS -c "$JAR" -b "$JAR" "$BASE/api/v1/auth/session" > /dev/null
curl -fsS -c "$JAR" -b "$JAR" -H "X-XSRF-TOKEN: $(token)" --data-urlencode username=admin \
  --data-urlencode "password=$ADMIN_PASSWORD" "$BASE/api/v1/auth/login" > /dev/null
curl -fsS -c "$JAR" -b "$JAR" "$BASE/api/v1/auth/session" > /dev/null
json() { python3 -c "import json, sys; d = json.load(sys.stdin); print($1)"; }
ID=$(curl -fsS -b "$JAR" -H "X-XSRF-TOKEN: $(token)" -H 'Content-Type: application/json' \
  -d '{"name":"compose smoke test","task":{"algorithm":"matrix.multiply","parameters":{"rowsA":200,"colsA":200,"colsB":200},"inputs":{},"execution":{"mode":"DISTRIBUTED_CPU"}}}' \
  "$BASE/api/v1/experiments" | json "d['summary']['id']")
STATUS=""
for _ in $(seq 1 60); do
  STATUS=$(curl -fsS -b "$JAR" "$BASE/api/v1/experiments/$ID" | json "d['summary']['status']")
  if [[ "$STATUS" =~ ^(COMPLETED|FAILED|CANCELLED)$ ]]; then break; fi
  sleep 1
done
[[ "$STATUS" == COMPLETED ]] || { echo "The experiment ended $STATUS" >&2; exit 1; }
VERIFIED=$(curl -fsS -b "$JAR" "$BASE/api/v1/experiments/$ID" | json "all(r['verificationPassed'] for r in d['runs'])")
[[ "$VERIFIED" == True ]] || { echo "The result was not verified" >&2; exit 1; }
WORKERS=$(curl -fsS -b "$JAR" "$BASE/api/v1/experiments/$ID" | json "d['summary']['workersUsed']")
echo "Compose smoke test passed: application healthy, 2 workers registered, distributed experiment $ID verified on $WORKERS workers."
