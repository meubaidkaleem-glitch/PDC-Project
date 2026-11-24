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

> Status: all sixteen phases of [docs/implementation-plan.md](docs/implementation-plan.md) are implemented; the
> [implementation report](docs/implementation-report.md) lists what was tested, what is limited and what is not
> provided.

## What it does

- Run a task from Core's catalog (matrix operations, numerical methods, data processing) with generated, pasted or
  uploaded data, sequentially, on threads or on worker machines; Core validates it first.
- See the advisor's recommendation for the task with its reasons and the basis of every estimate, and record why
  when you choose otherwise.
- Watch runs live (partitions, attempts, workers, events), read verified results and every measurement.
- Compare experiments: speedup, efficiency, variation, time breakdown, with the caveats that apply; run benchmark
  batches one experiment after another.
- Download experiment and comparison reports as PDF, CSV or JSON.
- Manage worker processes; for administrators, a fault lab on an isolated coordinator, settings and users.

## Quick start with Docker Compose

Requirements: Git, JDK 21, Node.js 22, Docker.

```bash
git clone --recurse-submodules --branch feature/application-gui https://github.com/meubaidkaleem-glitch/PDC-Project.git districompute
```

```bash
cd districompute && scripts/build.sh
```

```bash
cp .env.example .env
```

Set `DISTRICOMPUTE_DB_PASSWORD` (and optionally `DISTRICOMPUTE_ADMIN_PASSWORD`) in `.env`, then:

```bash
docker compose up -d --build
```

Open <http://localhost:8080> and sign in as `admin` (password from `.env`, or
`docker compose exec app cat /data/initial-admin-password`). Two workers are running;
`docker compose up -d --scale worker=4` adds more. [docs/deployment.md](docs/deployment.md) covers running without
Docker, workers on other machines and operations.

## Tests

```bash
scripts/build.sh
```

runs the frontend tests and the backend's unit, persistence and integration tests (MySQL in Testcontainers, so
Docker is needed).

```bash
scripts/e2e.sh
```

runs the Playwright end-to-end tests against the packaged application, MySQL and worker processes, and

```bash
scripts/compose-smoke.sh
```

checks the Docker Compose deployment. [docs/testing.md](docs/testing.md) describes the suites.

## Documentation

| Document | Contents |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Repository inspection, decisions, logical architecture, security model |
| [docs/implementation-plan.md](docs/implementation-plan.md) | Phases, acceptance criteria, status |
| [docs/integration.md](docs/integration.md) | Submodules, pinned versions, build, component change requests, updating a component |
| [docs/api.md](docs/api.md) | REST API conventions (authentication, CSRF, errors, paging, live events) and endpoints |
| [docs/database.md](docs/database.md) | Schema, relationships, constraints, file storage, migrations |
| [docs/advisor.md](docs/advisor.md) | How execution strategies are assessed, estimate basis and confidence, overrides |
| [docs/metrics.md](docs/metrics.md) | Every stored metric: name, unit, source, definition |
| [docs/analysis.md](docs/analysis.md) | Comparison: basis, baseline, speedup, efficiency, breakdown, compatibility checks |
| [docs/reports.md](docs/reports.md) | Experiment and comparison reports, history export: contents and formats |
| [docs/fault-lab.md](docs/fault-lab.md) | Fault lab: isolation, scenarios, outcome, measured behaviour |
| [docs/batches-and-templates.md](docs/batches-and-templates.md) | Templates and batches run in order |
| [docs/testing.md](docs/testing.md) | Test suites, what they cover, how to run them, CI |
| [docs/user-guide.md](docs/user-guide.md) | Using the interface: tasks, advisor, monitoring, comparison, batches, reports, workers |
| [docs/deployment.md](docs/deployment.md) | Build, Docker Compose, running without Docker, operations |
| [docs/configuration.md](docs/configuration.md) | Every setting and environment variable |
| [docs/security.md](docs/security.md) | Authentication, access, input, workers, secrets, audit, what to add in production |
| [docs/workers.md](docs/workers.md) | Starting and managing worker processes, TLS |
| [docs/fault-tolerance.md](docs/fault-tolerance.md) | Failures at each level and how they are handled and tested |
| [docs/implementation-report.md](docs/implementation-report.md) | What was built and tested, known limitations, component versions |
| [CHANGELOG.md](CHANGELOG.md) | Release notes |
