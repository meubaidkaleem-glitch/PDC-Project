#!/usr/bin/env bash
# End-to-end tests (docs/testing.md): Playwright drives the interface of the packaged backend, which runs with two
# independent worker processes from the DistriCompute-Distributed jar and a MySQL 8.4 database.
#
#   scripts/build.sh && scripts/e2e.sh [playwright options]
#
# Without DISTRICOMPUTE_DB_URL a throwaway MySQL container is started with Docker (and removed afterwards); with it,
# the given database must be empty. Logs of the backend and the workers stay in the printed directory.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)

BACKEND_JAR=$(ls "$ROOT"/backend/target/districompute-backend-*.jar 2>/dev/null | grep -v '\.original$' | head -n 1 || true)
WORKER_JAR=$(ls "$ROOT"/components/distributed/distributed/target/districompute-distributed-*-all.jar 2>/dev/null | head -n 1 || true)
if [[ -z "$BACKEND_JAR" || -z "$WORKER_JAR" ]]; then
  echo "The backend jar or the worker jar is missing; run scripts/build.sh first." >&2
  exit 1
fi
if ! unzip -l "$BACKEND_JAR" | grep 'BOOT-INF/classes/static/index.html' > /dev/null; then
  echo "$BACKEND_JAR does not contain the interface; build it with scripts/build.sh (profile with-frontend)." >&2
  exit 1
fi

E2E_DIR=$(mktemp -d "${TMPDIR:-/tmp}/districompute-e2e.XXXXXX")
CONTAINER=""
cleanup() {
  if [[ -n "$CONTAINER" ]]; then
    docker rm -f "$CONTAINER" > /dev/null 2>&1 || true
  fi
  echo "Backend and worker logs: $E2E_DIR/logs"
}
trap cleanup EXIT

if [[ -z "${DISTRICOMPUTE_DB_URL:-}" ]]; then
  DB_PORT=${E2E_DB_PORT:-13306}
  DB_PASSWORD=$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')
  CONTAINER="districompute-e2e-mysql-$$"
  docker run -d --name "$CONTAINER" -e MYSQL_DATABASE=districompute -e MYSQL_USER=districompute \
    -e MYSQL_PASSWORD="$DB_PASSWORD" -e MYSQL_RANDOM_ROOT_PASSWORD=yes -p "127.0.0.1:$DB_PORT:3306" mysql:8.4 > /dev/null
  echo "Waiting for MySQL ($CONTAINER)..."
  for _ in $(seq 1 90); do
    # Over TCP, so that the temporary server of the image's initialisation does not count as ready.
    if docker exec "$CONTAINER" mysql -h 127.0.0.1 -udistricompute -p"$DB_PASSWORD" -e 'SELECT 1' districompute \
        > /dev/null 2>&1; then
      break
    fi
    sleep 2
  done
  export DISTRICOMPUTE_DB_URL="jdbc:mysql://127.0.0.1:$DB_PORT/districompute"
  export DISTRICOMPUTE_DB_USER=districompute
  export DISTRICOMPUTE_DB_PASSWORD="$DB_PASSWORD"
fi

export E2E_DIR E2E_BACKEND_JAR="$BACKEND_JAR" E2E_WORKER_JAR="$WORKER_JAR"
cd "$ROOT/frontend"
npx playwright test --trace off "$@"
