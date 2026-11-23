#!/usr/bin/env bash
# Runs every MPI demonstration and stops at the first failure.
#   NP=4 ./run-demos.sh                  all demos on this machine
#   NP=8 HOSTFILE=hosts ./run-demos.sh   across the machines listed in a hostfile
#   MPI_IMPL=openmpi|other               skip the detection of the MPI implementation
set -euo pipefail
cd "$(dirname "$0")"
NP="${NP:-4}"
MPIRUN="$(command -v mpirun)" || { echo "mpirun not found; install an MPI implementation" >&2; exit 1; }

# Open MPI needs extra options. It is recognised by its version text or, because some distribution packages lack
# the help files that this text comes from (Ubuntu's Open MPI 5.0.10 prints "Sorry! You were supposed to get help
# about: version"), by the installed path of mpirun (for example /usr/bin/mpirun.openmpi) or by ompi_info.
is_open_mpi() {
  case "${MPI_IMPL:-}" in
    openmpi) return 0 ;;
    "") ;;
    *) return 1 ;;
  esac
  mpirun --version 2>&1 | grep -Eq "Open MPI|OpenRTE" && return 0
  readlink -f "$MPIRUN" | grep -Eqi "openmpi|orterun|prterun" && return 0
  command -v ompi_info > /dev/null 2>&1
}

ARGS=(-np "$NP")
if is_open_mpi; then
  echo "MPI implementation: Open MPI ($(readlink -f "$MPIRUN"))"
  # Open MPI starts at most one process per detected core unless slots are declared. Without a hostfile, declare
  # NP slots on this machine.
  if [ -z "${HOSTFILE:-}" ]; then
    ARGS+=(--host "localhost:$NP")
  fi
  # Open MPI refuses to run as root unless told otherwise (for example inside a container).
  if [ "$(id -u)" = "0" ]; then
    ARGS+=(--allow-run-as-root)
  fi
else
  echo "MPI implementation: not Open MPI ($(readlink -f "$MPIRUN")); using plain mpirun -np $NP"
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
