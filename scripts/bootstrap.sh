#!/usr/bin/env bash
# Initializes the component submodules at the commits pinned by this branch and verifies them
# (docs/integration.md). Safe to run repeatedly.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

command -v git > /dev/null || { echo "git is required" >&2; exit 1; }
git submodule sync --recursive > /dev/null
git submodule update --init

status=0
for path in components/core components/distributed; do
  pinned="$(git ls-tree HEAD "$path" | awk '{print $3}')"
  actual="$(git -C "$path" rev-parse HEAD)"
  if [ -z "$pinned" ]; then
    echo "$path: not recorded in this commit (uncommitted submodule?)" >&2
    status=1
  elif [ "$pinned" != "$actual" ]; then
    echo "$path: checked out at ${actual:0:7}, but this branch pins ${pinned:0:7}." >&2
    echo "  Run: git submodule update $path" >&2
    status=1
  else
    echo "$path: ${actual:0:7} (pinned)"
  fi
done

if command -v java > /dev/null; then
  java -version 2>&1 | grep -v "^Picked up" | head -1
else
  echo "warning: java not found (JDK 21 is required)" >&2
fi
if command -v node > /dev/null; then
  echo "node $(node --version)"
else
  echo "warning: node not found (Node.js 22 or newer is required for the frontend)" >&2
fi
exit $status
