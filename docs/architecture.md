# Architecture

DistriCompute is an adaptive heterogeneous computing platform built by three contributors. This repository
branch is **DistriCompute-Application** (Contributor 3): the user-facing application and the integration point of
the other two components.

| Component | Owner | Where | Pinned at |
|---|---|---|---|
| DistriCompute-Core | Contributor 1 | Submodule `components/core` (branch `feature/core-engine`) | See [integration.md](integration.md#2-pinned-component-versions) |
| DistriCompute-Distributed | Contributor 2 | Submodule `components/distributed` (branch `feature/distributed-engine`) | See [integration.md](integration.md#2-pinned-component-versions) |
| DistriCompute-Application | Contributor 3 | This branch, `feature/application-gui` | — |

## 1. Repository inspection

* The project is one GitHub repository, `PDC-Project`, with one branch per component. The three "repositories" of the
  specification are therefore three branches. The component branches share history (Distributed was started from
  Core). The application branch starts from the repository's initial commit and contains no component source code.
* The submodules point to the same repository URL, each pinned to a commit of its component branch. A clean clone
  plus `git submodule update --init` reproduces exactly the pinned components.
* **Core** ([components/core](../components/core)) is a Java 21 library: an algorithm registry with 10 algorithms
  in 5 categories, parameter validation, workload analysis (`WorkloadProfile`), local sequential and multithreaded
  execution, partitioning, result verification, metrics and a strategy advisor. Its contract is
  `components/core/docs/integration-contract.md`.
* **Distributed** ([components/distributed/distributed](../components/distributed/distributed)) is a Java 21
  runtime: a coordinator (embeddable, or standalone with an HTTP API) and worker processes speaking a versioned
  JSON-over-TCP protocol, with scheduling policies, fault tolerance, metrics, events, fault simulation and
  execution advice. Its contract is `components/distributed/distributed/docs/integration-contract.md`.
* Both components use Jackson 3.2.4 and are built with Maven. Neither is published to a Maven repository.

## 2. Decisions

| # | Decision | Reason |
|---|---|---|
| D1 | **Build from source in one Maven reactor**: the root `pom.xml` lists `components/core`, `components/distributed/distributed` and `backend` as modules | The application never depends on unpublished local binaries. The component POMs are used unchanged. |
| D2 | **Embed the Distributed coordinator in the backend** (Distributed contract §2.4, "embedded" mode) | All execution, sequential, multithreaded and distributed, goes through one runtime with one event stream. Workers are separate processes that connect to the backend's coordinator port. Nothing is reimplemented. |
| D3 | **No mock adapter** | Both components were complete before the application started, so the application uses them from the first phase. No screen shows sample data as live data. |
| D4 | **Spring Boot 4.1.3** (Java 21), MySQL 8.4 with Flyway, JPA | Spring Boot 4 uses Jackson 3 like the components. 4.1.3 is the latest patch of the previous minor line; 4.2.0 was four days old when the project started. |
| D5 | **Jackson pinned to 3.2.4** in the backend | Spring Boot 4.1.3 manages 3.1.8, which would downgrade the components. |
| D6 | **Server-Sent Events** for live updates | Updates flow one way (server to browser). SSE works through proxies, reconnects by itself and resumes with `Last-Event-ID`. The Distributed runtime already numbers its events. |
| D7 | **Results on the file system, references in MySQL** | Matrices and arrays can be hundreds of megabytes. Only metadata, the digest and a storage reference are relational. |
| D8 | **Session authentication with CSRF protection**, roles `ADMIN` and `USER`, passwords hashed with BCrypt | The browser application needs no token handling. Users see their own experiments; administrators manage workers, settings and the fault lab. |
| D9 | **Identifiers**: an experiment id (UUID) is Core's `experimentId`; each run's Core `taskId` is `<experimentId>-r<n>` | The same identifiers appear in the database, Core results, Distributed snapshots and runtime events. |
| D10 | **React 19, Vite 8, TypeScript 5.9, Tailwind CSS 4, Recharts 3, lucide-react, React Router 7**, tested with Vitest and Playwright | Stable lines with mutually compatible peers. TypeScript 7 and React Router 8 were too new to rely on. |

## 3. Logical architecture

```
 Browser ── React SPA (frontend/) ───────────────────────────────────────────────┐
                │ REST /api/v1 (JSON)            ▲ Server-Sent Events             │ static files
                ▼                                │                                │
 ┌─────────────────────────── Spring Boot backend (backend/) ──────────────────────────────┐
 │ catalog      algorithms, categories, parameter schemas     ── Core AlgorithmRegistry     │
 │ tasks        validation, input files, workload analysis     ── Core validation/analyze   │
 │ advisor      recommendation + history + limits              ── Coordinator.advise (Core  │
 │                                                                StrategyAdvisor + workers)│
 │ experiments  experiments, runs, repetitions, batches, templates                          │
 │ execution    submission, run tracking, result storage       ── Coordinator.submit        │
 │ monitoring   event persistence, SSE fan-out                 ── Coordinator.events()      │
 │ workers      registry view, approval, deactivation, history ── Coordinator.workers()     │
 │ analysis     metrics, comparison, compatibility checks                                   │
 │ reports      PDF, CSV, JSON                                                              │
 │ faultlab     isolated coordinator + simulated workers       ── Distributed fault profiles│
 │ security     users, roles, sessions, audit                                               │
 │                                                                                          │
 │   Embedded Distributed Coordinator ── TCP 7650 (worker protocol, HMAC, optional TLS)     │
 │   (runs local modes through Core's LocalExecutionEngine on its own threads)              │
 └───────────┬──────────────────────────────────────────────┬───────────────────────────────┘
             │ JDBC (Flyway-managed schema)                 │ worker protocol
             ▼                                              ▼
        MySQL 8.4                                  Worker processes (Distributed jar), one per machine
        result files (data/results)                executing Core partitions
```

**Threading.** HTTP request threads never compute. `POST /api/v1/experiments` validates, stores and submits,
then returns `202 Accepted`. The coordinator runs the task (on its own threads or on workers) and completes a
future. The application then stores the result and metrics on its own executor. Runtime events arrive on the
event bus's delivery thread. They are persisted in batches and pushed to SSE subscribers.

## 4. What the application adds, and what it does not do

| Concern | Application | Delegated to |
|---|---|---|
| Algorithms, validation, workload analysis, verification | Presentation, application limits, input files | Core |
| Strategy assessment | History from the database, configured limits, estimate basis, override recording | Core `StrategyAdvisor` via `Coordinator.advise` |
| Scheduling, dispatch, retries, failure detection | Configuration and presentation | Distributed |
| Worker discovery and capabilities | Approval list, deactivation, history, topology | Distributed registry |
| Metrics | Storage, derived values (speedup, efficiency), comparison | Core `ExecutionMetrics`, Distributed `DistributedMetrics` |
| Results | Storage, preview, download, digest check | Core result values and digests |

## 5. Gaps found in the components

Inspection found two needs of the application that the Distributed API does not cover:

1. **Full result values.** `TaskOutcome` contains Core's `TaskResult` (summary and digest), but not the value, so
   the application could not store or preview a matrix or a sorted array.
2. **Deactivating and removing workers.** The coordinator has no operation to stop scheduling on a worker or to
   disconnect it.

Both are additive changes to the Distributed component, made in its own branch with tests and documentation
([integration.md §4](integration.md#4-changes-requested-from-the-components)), and then pinned here. Core
needs no change.

## 6. Security model (summary)

* No user input is ever executed as code. Tasks name a registered Core algorithm and give parameters that Core
  validates. Polynomials typed as text are parsed by a restricted grammar into coefficient arrays.
* Uploaded files are size-limited, type-checked, parsed into numeric arrays and never stored under user-chosen
  names.
* Workers authenticate with the cluster secret (Distributed protocol). The application can additionally require
  administrators to approve worker ids.
* Results are verified by Core. The application recomputes the digest of every stored value and compares it with
  the verified digest.
* Secrets (database password, cluster secret, administrator bootstrap password) come from the environment or files
  and are never stored in plain text or returned by the API.

Details: [security.md](security.md); failures at each level: [fault-tolerance.md](fault-tolerance.md).
