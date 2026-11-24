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

## Run locally

The backend needs MySQL 8.4. For a local trial, start it in Docker:

```bash
docker run -d --name districompute-mysql -e MYSQL_DATABASE=districompute -e MYSQL_USER=districompute -e MYSQL_PASSWORD=change-me -e MYSQL_RANDOM_ROOT_PASSWORD=yes -p 127.0.0.1:3306:3306 mysql:8.4
```

Start the application (the jar built by `scripts/build.sh` serves the interface on port 8080). On first start it
creates the user `admin`; put its password (at least 12 characters) in a file first, or read the generated one
from `data/initial-admin-password`:

```bash
DISTRICOMPUTE_DB_PASSWORD=change-me java -jar backend/target/districompute-backend-0.1.0-SNAPSHOT.jar --districompute.data-dir=data --districompute.security.admin-password-file=admin.password
```

Workers are separate processes from the Distributed jar. They authenticate with the cluster secret the
application generated in `data/cluster.secret` and connect to the worker port (7650 by default):

```bash
java -jar components/distributed/distributed/target/districompute-distributed-0.1.0-SNAPSHOT-all.jar worker --coordinator 127.0.0.1:7650 --id worker-1 --max-concurrent-tasks 2 --secret-file data/cluster.secret
```

Then open <http://localhost:8080>. The Workers page also shows the exact start command for a registered worker.

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
