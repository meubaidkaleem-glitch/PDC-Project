#!/bin/sh
# DistriCompute for Linux (docs/standalone.md). Starts the application with the Java runtime in this folder:
# nothing else needs to be installed. Press Ctrl+C to stop it.
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PORT=${DISTRICOMPUTE_HTTP_PORT:-8080}
DATA=${DISTRICOMPUTE_DATA_DIR:-${XDG_DATA_HOME:-$HOME/.local/share}/districompute}

# Already running? Then only open it in the browser.
if command -v curl >/dev/null 2>&1 \
    && curl --silent --fail --max-time 3 "http://127.0.0.1:$PORT/api/v1/system/info" 2>/dev/null | grep -q '"distributed"'; then
  echo "DistriCompute is already running: http://localhost:$PORT/"
  if [ -z "${DISTRICOMPUTE_NO_BROWSER:-}" ]; then (xdg-open "http://localhost:$PORT/" >/dev/null 2>&1 &) || true; fi
  exit 0
fi

mkdir -p "$DATA"
export DISTRICOMPUTE_HTTP_PORT="$PORT"
echo "Starting DistriCompute. Your browser opens when it is ready; this takes a few seconds."
if [ -n "${DISTRICOMPUTE_NO_BROWSER:-}" ]; then set -- --districompute.standalone.open-browser=false "$@"; fi
exec "$HERE/runtime/bin/java" -jar "$HERE/app/districompute.jar" --spring.profiles.active=standalone \
  "--districompute.data-dir=$DATA" "$@"
