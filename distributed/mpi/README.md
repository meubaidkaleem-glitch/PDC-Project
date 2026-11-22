# MPI Demonstrations

Four C programs that demonstrate MPI point-to-point communication, broadcast, scatter, gather and a simple
parallel computation. They are kept **separate** from DistriCompute's Java runtime. They show an
alternative distributed model, in which every process runs the same program (SPMD) with collective
operations, next to the runtime's coordinator–worker model. They are not a second production runtime.

| Program | MPI operations | What it shows and verifies |
|---|---|---|
| `point_to_point.c` | `MPI_Send`, `MPI_Recv` | A token passed around a ring of all processes (rank 0 checks the sum of ranks). A ping-pong between ranks 0 and 1 with messages from 1 B to 1 MiB, reporting round-trip latency and bandwidth and checking every payload. |
| `broadcast.c` | `MPI_Bcast`, `MPI_Reduce` | Rank 0 broadcasts the parameters and an 8 MiB array; every process checks a checksum. The same distribution done with one `MPI_Send` per process, for comparison. |
| `scatter_gather.c` | `MPI_Scatterv`, `MPI_Gatherv`, `MPI_Reduce` | Uneven chunks of 1 000 003 elements (the remainder rule of Core's range partitioner), a local transform and partial sums, gathered and reduced on rank 0 and checked against a sequential computation |
| `parallel_primes.c` | `MPI_Bcast`, `MPI_Reduce` | A simple parallel computation: π(n) with a segmented sieve per block, checked against the known value and a sequential count, with speedup and efficiency |

Every program exits with status 1 if any check fails, so a broken result can never look like success.

## Build and run on one machine

You need a C compiler and an MPI implementation (Open MPI or MPICH). On Ubuntu or Debian:

```bash
sudo apt-get install -y openmpi-bin libopenmpi-dev
```

```bash
cd distributed/mpi && make
```

```bash
NP=4 ./run-demos.sh
```

`run-demos.sh` adds `--oversubscribe` for Open MPI, so more processes than cores still start, and
`--allow-run-as-root` when run as root (for example in a container). Each program can also be started on its own,
for example `mpirun -np 4 ./parallel_primes 1000000000`.

## Run across several machines

1. Install the same MPI implementation on every machine, and put the compiled programs at the same path on each
   (or on a shared directory).
2. Set up password-less SSH from the first machine to the others. Open MPI starts the remote processes over SSH.
3. Write a hostfile with one line per machine:
   ```
   lab-pc-01 slots=4
   lab-pc-02 slots=4
   ```
4. Run:
   ```bash
   NP=8 HOSTFILE=hosts ./run-demos.sh
   ```

Firewalls must allow the TCP ports Open MPI chooses, or a fixed range set with
`--mca btl_tcp_port_min_v4 … --mca btl_tcp_port_range_v4 …`.

## Output from a real run

Measured in the development container (4 vCPUs, Open MPI 4.1.6, all processes on one machine, so messages use
shared memory, not a network) on 2026-11-22. The numbers will differ on other machines and on a real network,
where latency is much higher.

```
=== point_to_point (np=4) ===
ring: token visited 4 processes, sum of ranks 6 (expected 6) OK
ping-pong between ranks 0 and 1 (50 round trips per size):
       bytes    latency (us)  bandwidth (MB/s)
           1            1.22             1.6
          16            0.72            44.7
         256            0.80           640.6
        4096            5.40          1517.1
       65536           25.91          5059.3
     1048576          251.03          8354.2
point_to_point: all messages verified
=== broadcast (np=4) ===
broadcast: 1048576 elements (8.0 MiB) to 4 processes
  MPI_Bcast            13.582 ms
  linear MPI_Send       6.003 ms
  checksum bfcb7323cbabe35e on every process: OK
=== scatter_gather (np=4) ===
scatter_gather: 1000003 elements over 4 processes (chunks 250000..250001)
  gathered transform: 0 wrong elements
  reduced sum of squares 6792520408.265215, sequential 6792520408.265139, relative difference 1.12e-14
  scatter + compute + gather + reduce: 15.811 ms -> OK
=== parallel_primes (np=4) ===
parallel_primes: pi(100000000) with 4 processes
  parallel count   5761455 in 0.042 s
  sequential count 5761455 in 0.110 s (rank 0 alone)
  known value      5761455
  speedup 2.61, efficiency 0.65 -> OK
All MPI demonstrations completed and verified.
```

**Reading the numbers honestly.**

* One run on one shared machine is not a benchmark.
* `MPI_Bcast` was slower than the linear sends here. With only 4 processes in shared memory, a tree algorithm
  has little to gain and some setup cost. On a network with many processes, the tree's `log p` steps are
  expected to win.
* The sum of squares differs from the sequential sum by 1e-14 relative, because floating-point addition in a
  different order rounds differently. That is why the check uses a tolerance there and exact equality everywhere
  else.
* The speedup of π(10⁸) is measured against rank 0 counting alone while the other processes wait. On busy
  hardware it varies between runs.

## MPI and the Java runtime compared

| | MPI demos | DistriCompute-Distributed |
|---|---|---|
| Model | SPMD: every process runs the same program; data moves through collectives | Coordinator and workers; the coordinator sends work units and collects results |
| Membership | Fixed when `mpirun` starts | Workers join and leave at any time |
| Faults | A failed process normally aborts the whole job | Detected and recovered (retries, timeouts, quarantine) |
| Load balancing | Static blocks, decided in the program | Dynamic, by scheduling policy |
| Communication | Optimized transports (shared memory, RDMA) and collectives | JSON over TCP; simple to inspect, with a measurable cost |
| Algorithms | Written in each program | Core's verified Java algorithms |

MPI is the better tool for tightly coupled, long-running numerical jobs on a fixed set of machines. The Java
runtime trades raw communication speed for dynamic membership, fault tolerance and integration with the
Application.

## Continuous integration

The `mpi-demos` job in `.github/workflows/distributed-ci.yml` installs Open MPI on the GitHub runner, builds
with `-Wall -Wextra -Werror`, and runs every demo with 4 processes. The job fails if any check fails.
