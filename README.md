# DistriCompute-Core

> GitHub repository: `PDC-Project`. This repository is the **DistriCompute-Core** component of the DistriCompute
> project (see [docs/architecture.md §2.1](docs/architecture.md#21-repository-naming)).

DistriCompute is an adaptive heterogeneous platform for distributed parallel computing and performance
analysis. **Core** is its computational engine: a plain Java 21 library that provides

* a universal task model and lifecycle shared by all DistriCompute components;
* an algorithm abstraction and registry;
* sequential and bounded multithreaded execution;
* partitioning, aggregation and result verification;
* execution metrics and benchmarking.

The distributed runtime ([DistriCompute-Distributed]) and the application ([DistriCompute-Application]) use
Core as a library. Core has no dependency on Spring, a database, a UI or the network.

## Status

All planned Core phases are implemented on `feature/core-engine` (see the
[implementation report](docs/implementation-report.md) for exact results and limitations):

| Area | Status |
|---|---|
| Task model, lifecycle, validation, registry, error model, JSON contracts | Done |
| 10 verified algorithms: primes, merge sort, matrix add and multiply, integration, polynomial evaluation, root finding, Monte Carlo, statistics, filter | Done |
| `SEQUENTIAL_CPU` and `MULTITHREADED_CPU` (bounded worker pool, partitioning, aggregation) | Done |
| `DISTRIBUTED_CPU` Core side: coordinator and worker facade, payload codecs | Done; tested with a simulated runtime |
| Benchmark framework, methodology, measured results | Done |
| Strategy advisor | Done |
| GPU | Interface and detection only. **No GPU execution** (no hardware to validate on) |

The integration contract is a **draft (`0.1`)** until Contributors 2 and 3 agree on it.

## Quick start

```bash
./mvnw -q test-compile exec:java -Dexec.args="catalog"
```

```bash
./mvnw -q test-compile exec:java -Dexec.args="run examples/tasks/matmul-multithreaded.json"
```

```bash
./mvnw -q test-compile exec:java -Dexec.args="advise examples/tasks/matmul-multithreaded.json"
```

As a library:

```java
try (LocalExecutionEngine engine = LocalExecutionEngine.withDefaults()) {
    TaskSpec task = TaskSpec.builder("matrix.multiply")
            .parameter("rowsA", 512).parameter("colsA", 512).parameter("colsB", 512)
            .mode(ExecutionMode.MULTITHREADED_CPU).maxThreads(4)
            .build();
    ExecutionOutcome outcome = engine.execute(task);
    System.out.println(ContractJson.toPrettyJson(outcome.taskResult()));   // status, digest, verification, metrics
}
```

## Integration

* **Application (Contributor 3).** Add this repository as a pinned Git submodule and include it in the Maven
  aggregator. See [integration-contract §16](docs/integration-contract.md#16-examples-and-how-to-integrate).
* **Distributed runtime (Contributor 2).** Use `PartitionedTaskSession` and `PartitionWorker`. See
  [integration-contract §14](docs/integration-contract.md#14-core-facade-for-the-distributed-runtime).

## Requirements

* JDK 21 or newer (CI uses Eclipse Temurin 21).
* No local Maven installation is needed: the Maven Wrapper (`./mvnw`) downloads the pinned Maven 3.9.11.

## Build and test

```bash
./mvnw verify
```

`verify` compiles with `-Xlint:all -Werror`, runs the unit tests, writes a JaCoCo coverage report to
`target/site/jacoco/`, and runs the Enforcer, Checkstyle and SpotBugs checks.

To run the concurrency test suite on its own (CI runs it as a separate step):

```bash
./mvnw test -Dtest.groups=concurrency -Dtest.excludedGroups=performance
```

Performance-scenario tests (different sizes, threads, partitionings and block sizes; correctness assertions only):

```bash
./mvnw test -Dtest.groups=performance -Dtest.excludedGroups=
```

To run a benchmark suite and write JSON, CSV and Markdown reports:

```bash
./mvnw -q test-compile exec:java -Dexec.args="benchmark benchmarks/suites/matmul-scaling.json target/benchmarks"
```

## Documentation

| Document | Contents |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Repository review, responsibilities, dependencies, architecture, risks |
| [docs/implementation-plan.md](docs/implementation-plan.md) | Phases, acceptance criteria, postponed features, status |
| [docs/task-model.md](docs/task-model.md) | Task specification, lifecycle, parameter validation, algorithm SPI and registry |
| [docs/algorithms.md](docs/algorithms.md) | Algorithms, parameters, input generation, verification, limitations |
| [docs/execution-model.md](docs/execution-model.md) | Execution modes, engine lifecycle, failure handling, memory guard, cancellation |
| [docs/performance-methodology.md](docs/performance-methodology.md) | Metric definitions, repeated trials, speedup and efficiency, environment, rules |
| [docs/gpu-backend.md](docs/gpu-backend.md) | GPU status, technology comparison, integration path |
| [docs/integration-contract.md](docs/integration-contract.md) | Proposed shared contracts (task, status, partitions, results, errors, metrics) |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Branches, commits, pull requests, definition of done |
| [CHANGELOG.md](CHANGELOG.md) | Release notes |
| [docs/implementation-report.md](docs/implementation-report.md) | What is done, partial and missing; test results; limitations |
| [examples/](examples) | Example task and result documents |
| [benchmarks/](benchmarks) | Benchmark suites and measured results |

## License

MIT. See [LICENSE](LICENSE). *The license choice still needs the team's confirmation.*

[DistriCompute-Distributed]: docs/architecture.md#5-dependencies-between-repositories
[DistriCompute-Application]: docs/architecture.md#5-dependencies-between-repositories
