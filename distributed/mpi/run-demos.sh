#!/usr/bin/env bash
# Runs every MPI demonstration and stops at the first failure.
#   NP=4 ./run-demos.sh            all demos on this machine
#   NP=8 HOSTFILE=hosts ./run-demos.sh   across the machines listed in a hostfile
set -euo pipefail
cd "$(dirname "$0")"
NP="${NP:-4}"
ARGS=(-np "$NP")
if mpirun --version 2>&1 | grep -q "Open MPI"; then
  # Open MPI starts at most one process per detected core unless slots are declared. Declaring NP slots on
  # this machine works with Open MPI 4 and 5 alike (5 no longer honours --oversubscribe the same way).
  if [ -z "${HOSTFILE:-}" ]; then
    ARGS+=(--host "localhost:$NP")
  fi
  # Open MPI refuses to run as root unless told otherwise (for example inside a container).
  if [ "$(id -u)" = "0" ]; then
    ARGS+=(--allow-run-as-root)
  fi
fi
if [ -n "${HOSTFILE:-}" ]; then
  ARGS+=(--hostfile "$HOSTFILE")
fi
for demo in point_to_point broadcast scatter_gather parallel_primes; do
  echo "=== $demo (np=$NP) ==="
  mpirun "${ARGS[@]}" "./$demo"
  echo
done
echo "All MPI demonstrations completed and verified."
