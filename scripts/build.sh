#!/usr/bin/env bash
# Builds the whole platform from the pinned components (docs/integration.md §3):
#   scripts/build.sh                    components, frontend, backend (with tests)
#   scripts/build.sh --component-tests  also run Core's and Distributed's test suites
#   scripts/build.sh --skip-tests       no tests at all (fast packaging)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

COMPONENT_TESTS=0
SKIP_TESTS=0
for arg in "$@"; do
  case "$arg" in
    --component-tests) COMPONENT_TESTS=1 ;;
    --skip-tests) SKIP_TESTS=1 ;;
    *) echo "Unknown option $arg" >&2; exit 2 ;;
  esac
done

scripts/bootstrap.sh

echo "== Components (Core, Distributed) from the pinned submodules"
if [ "$COMPONENT_TESTS" = 1 ] && [ "$SKIP_TESTS" = 0 ]; then
  ./mvnw -B -ntp install -pl components/core,components/distributed/distributed
else
  ./mvnw -B -ntp install -pl components/core,components/distributed/distributed -DskipTests
fi

PROFILE=""
if [ -f frontend/package.json ]; then
  echo "== Frontend"
  (
    cd frontend
    npm ci --no-audit --no-fund
    npm run typecheck
    if [ "$SKIP_TESTS" = 0 ]; then npm test; fi
    npm run build
  )
  PROFILE="-Pwith-frontend"
fi

echo "== Backend"
if [ "$SKIP_TESTS" = 1 ]; then
  ./mvnw -B -ntp package -pl backend $PROFILE -DskipTests
else
  ./mvnw -B -ntp verify -pl backend $PROFILE
fi

echo
echo "Application: backend/target/districompute-backend-0.1.0-SNAPSHOT.jar"
echo "Worker:      components/distributed/distributed/target/districompute-distributed-0.1.0-SNAPSHOT-all.jar"
