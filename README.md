# DistriCompute

DistriCompute is an adaptive heterogeneous computing platform: users submit computational workloads (linear
algebra, numerical methods, data processing), see how the workload can be executed (sequentially, on several
threads, or across independent worker machines), run it, watch it live, and compare and report the measured
performance.

This branch, `feature/application-gui`, is **DistriCompute-Application** (Contributor 3): the web application and
the integration of the two other components, which are included as pinned Git submodules:

| Component | Path | Owner |
|---|---|---|
| DistriCompute-Core: algorithms, validation, workload analysis, local execution, verification | [components/core](components/core) | Contributor 1 |
| DistriCompute-Distributed: coordinator, worker processes, scheduling, fault tolerance | [components/distributed](components/distributed) | Contributor 2 |
| DistriCompute-Application: Spring Boot backend, React frontend, MySQL | [backend](backend), [frontend](frontend), [database](database) | Contributor 3 |

> Status: under development. See [docs/implementation-plan.md](docs/implementation-plan.md) for what is done.

## Build

Requirements: JDK 21, Node.js 22+, Git; Docker for the integration tests.

```bash
git clone --branch feature/application-gui https://github.com/meubaidkaleem-glitch/PDC-Project.git districompute
```

```bash
cd districompute && scripts/build.sh
```

## Documentation

| Document | Contents |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Repository inspection, decisions, logical architecture, security model |
| [docs/implementation-plan.md](docs/implementation-plan.md) | Phases, acceptance criteria, status |
| [docs/integration.md](docs/integration.md) | Submodules, pinned versions, build, component change requests, updating a component |
