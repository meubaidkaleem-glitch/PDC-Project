# DistriCompute-Distributed

The distributed runtime of DistriCompute (Contributor 2): a coordinator and independent worker processes that
communicate over a versioned JSON-over-TCP protocol. Workers register their capabilities and execute
partitions of DistriCompute-Core tasks. The coordinator schedules, dispatches, recovers from failures,
aggregates and verifies results through Core, and reports progress and metrics to the Application.

> This directory is the DistriCompute-Distributed component. It lives next to DistriCompute-Core (the
> repository root) on branch `feature/distributed-engine`; see [docs/architecture.md](docs/architecture.md#12-layout-decision).

## Status

Development follows the phases in [docs/implementation-plan.md](docs/implementation-plan.md). The status table
there records only completed work.

## Build

Requirements: JDK 21 or newer. Maven is not needed: the repository's Maven Wrapper downloads it.

From the repository root:

```bash
./mvnw install -DskipTests
```

```bash
./mvnw -f distributed/pom.xml verify
```

The first command installs DistriCompute-Core from this checkout into the local Maven repository. The second
builds the runtime, runs the unit and integration tests, and runs Checkstyle and SpotBugs. It produces
`distributed/target/districompute-distributed-0.1.0-SNAPSHOT-all.jar`, a runnable jar that contains Core:

```bash
java -jar distributed/target/districompute-distributed-0.1.0-SNAPSHOT-all.jar version
```

## Run a coordinator

```bash
printf 'replace-with-a-long-random-secret\n' > cluster.secret
```

```bash
java -jar distributed/target/districompute-distributed-0.1.0-SNAPSHOT-all.jar coordinator --port 7650 --secret-file cluster.secret
```

On each worker machine (or several times on one machine, with different ids):

```bash
java -jar districompute-distributed-0.1.0-SNAPSHOT-all.jar worker --coordinator 192.168.1.10:7650 --id lab-pc-07 --secret-file cluster.secret
```

Every option can also come from a properties file (`--config coordinator.properties`, same names without the
dashes). The secret can come from the `DISTRICOMPUTE_SECRET` environment variable instead of a file.

## Submit a task

Start the coordinator with its HTTP API (`--http-port 8650 --http-token-file api.token`), then:

```bash
java -jar distributed/target/districompute-distributed-0.1.0-SNAPSHOT-all.jar advise task.json --api http://127.0.0.1:8650 --token-file api.token
```

```bash
java -jar distributed/target/districompute-distributed-0.1.0-SNAPSHOT-all.jar submit task.json --api http://127.0.0.1:8650 --token-file api.token --policy dynamic --wait
```

`task.json` is a Core `TaskSpec`. `status` lists the workers, the tasks and the attempt counters. The Application
embeds the coordinator through its Java API, or uses the same HTTP API; see
[docs/integration-contract.md](docs/integration-contract.md).

## Documentation

| Document | Contents |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Repository inspection, responsibilities, components, threading, identifiers, decisions, risks |
| [docs/implementation-plan.md](docs/implementation-plan.md) | Phases, acceptance criteria, status, postponed features |
| [docs/worker-lifecycle.md](docs/worker-lifecycle.md) | Starting workers, capabilities, states, heartbeats, execution, reconnection, shutdown |
| [docs/scheduling.md](docs/scheduling.md) | Dispatch model, eligibility rules, the five policies, decision records, adding a policy |
| [docs/fault-tolerance.md](docs/fault-tolerance.md) | Failure detection, recovery, retry policy, quarantine, slot accounting, limitations |
| [mpi/README.md](mpi/README.md) | MPI demonstrations in C (separate from the Java runtime): build, run, real output, comparison |
| [docs/network-protocol.md](docs/network-protocol.md) | Wire protocol: framing, envelope, message types, errors, authentication, examples |
| [docs/integration-contract.md](docs/integration-contract.md) | What the runtime takes from Core and offers the Application |
