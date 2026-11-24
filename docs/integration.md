# Integration of the Components

How the application assembles DistriCompute-Core and DistriCompute-Distributed, how to build it, and how to
update a component.

## 1. Layout

```
components/
  core/                      submodule → PDC-Project, branch feature/core-engine (DistriCompute-Core)
  distributed/               submodule → PDC-Project, branch feature/distributed-engine
    distributed/             DistriCompute-Distributed module (its branch also contains Core at its root;
                             that copy is not built here)
backend/                     Spring Boot application
frontend/                    React application
database/migrations/         Flyway migrations (packaged into the backend jar)
pom.xml                      reactor: components/core, components/distributed/distributed, backend
```

Both submodules use the same repository URL, `https://github.com/meubaidkaleem-glitch/PDC-Project.git`, because the
three components are branches of one repository (see [architecture.md §1](architecture.md#1-repository-inspection)).
No component source is copied into this branch.

## 2. Pinned component versions

| Component | Submodule | Commit | Branch | Version | Notes |
|---|---|---|---|---|---|
| Core | `components/core` | `f269bf6` | `feature/core-engine` | `0.1.0-SNAPSHOT`, contract schema `0.1` | Code identical to Core's integration commit `dd1f9ce` (later commits change documentation only) |
| Distributed | `components/distributed` | `fc5f20e` | `feature/distributed-engine` | `0.1.0-SNAPSHOT`, protocol `0.1` | Code identical to `1931fd0`, which adds the two requests in [§4](#4-changes-requested-from-the-components) (CI run 45215288130) |

`git submodule status` prints the exact commits. From Phase 3, the running backend also reports the component
versions it was built with at `GET /api/v1/system/info`.

## 3. Building

Requirements: JDK 21, Node.js 22 or newer with npm, Git. Docker is needed for the integration tests (MySQL in
Testcontainers) and for Docker Compose.

```bash
git clone --branch feature/application-gui https://github.com/meubaidkaleem-glitch/PDC-Project.git districompute
```

```bash
cd districompute && scripts/bootstrap.sh
```

`bootstrap.sh` initializes the submodules at their pinned commits (`git submodule update --init`) and checks that
each is at the commit recorded in this branch. `scripts/build.sh` then:

1. builds and installs Core and Distributed from the submodules with their own quality gates (`./mvnw install -pl
   components/core,components/distributed/distributed`). Their test suites run in their own CI; pass `--component-tests`
   to run them here too;
2. builds the frontend (`npm ci`, type check, tests, `vite build`);
3. builds the backend with the frontend packaged as static content (`-Pwith-frontend`) and runs its tests.

The result is `backend/target/districompute-backend-0.1.0-SNAPSHOT.jar` (the application) and
`components/distributed/distributed/target/districompute-distributed-0.1.0-SNAPSHOT-all.jar` (the worker).

## 4. Changes requested from the components

The application must not change the components' interfaces silently. When it needs something they do not offer,
the change is made in the component's own branch, with tests and documentation, and then pinned here.

While adding C1, a defect in Distributed was found and fixed in the same commit: finished tasks kept Core's
session (input, partial results and full value) in memory for as long as they were retained.

| # | Component | Need | Status |
|---|---|---|---|
| C1 | Distributed | The full result value of a completed task, so results can be stored, previewed and downloaded | Done in `1931fd0`: `Coordinator.submit(spec, options, ResultSink)` |
| C2 | Distributed | Deactivate, reactivate and remove (disconnect) a worker | Done in `1931fd0`: `deactivateWorker`, `activateWorker`, `disconnectWorker`, `forgetWorker`, status `DEACTIVATED` |

## 5. Updating a component

1. In the component's branch, make and test the change (its own CI must pass).
2. Here: `git -C components/<name> fetch && git -C components/<name> checkout <commit>`.
3. Run `scripts/build.sh` and the end-to-end tests (`docs/testing.md`).
4. Commit the new submodule pointer together with any application change it requires, and update
   [§2](#2-pinned-component-versions).

## 6. Contracts used

| Contract | Document | Used for |
|---|---|---|
| Core task, result, error, metrics, workload profile, strategy | `components/core/docs/integration-contract.md` | Building `TaskSpec`s, reading `TaskResult`s, validation messages, analysis |
| Distributed embedding, snapshots, events, advice | `components/distributed/distributed/docs/integration-contract.md` | Execution, monitoring, workers, advice |
| Worker deployment | `components/distributed/distributed/docs/deployment.md` | Starting workers against the application's coordinator |

Both contracts are drafts agreed on paper by their authors. The application uses them as they stand. Every
deviation or extension is listed in [§4](#4-changes-requested-from-the-components).
