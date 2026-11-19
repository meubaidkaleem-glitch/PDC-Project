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

Development follows a phased plan ([docs/implementation-plan.md](docs/implementation-plan.md)). The table
lists only what exists on this branch.

| Area | Status |
|---|---|
| Architecture review, plan, draft integration contract | Done (Phase 1) |
| Build, quality checks, CI | Done (Phase 1) |
| Task model, lifecycle, algorithm SPI, registry, error model, JSON codec | Done (Phase 2), see [docs/task-model.md](docs/task-model.md) |
| Algorithms | Not started |

The integration contract is a **draft (`0.1`)** until Contributors 2 and 3 agree on it.

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

## Documentation

| Document | Contents |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Repository review, responsibilities, dependencies, architecture, risks |
| [docs/implementation-plan.md](docs/implementation-plan.md) | Phases, acceptance criteria, postponed features, status |
| [docs/task-model.md](docs/task-model.md) | Task specification, lifecycle, parameter validation, algorithm SPI and registry |
| [docs/integration-contract.md](docs/integration-contract.md) | Proposed shared contracts (task, status, partitions, results, errors, metrics) |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Branches, commits, pull requests, definition of done |

## License

MIT. See [LICENSE](LICENSE). *The license choice still needs the team's confirmation.*

[DistriCompute-Distributed]: docs/architecture.md#5-dependencies-between-repositories
[DistriCompute-Application]: docs/architecture.md#5-dependencies-between-repositories
