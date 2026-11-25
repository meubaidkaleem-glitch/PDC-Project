# Changelog

## 0.1.0-preview.1 — Windows and Linux packages

The application as one download that needs no installation ([docs/standalone.md](docs/standalone.md)).

### Added

- **Standalone mode** (profile `standalone`): embedded H2 database in the data folder with its own migrations
  (`database/migrations-h2`, same schema as MySQL), two workers started inside the application and connected to its
  coordinator over loopback TCP, the browser opened when ready, the address and the initial password (while still
  valid) on the console, web and worker ports bound to 127.0.0.1, fault lab enabled.
- **Packages** built by `scripts/package-standalone.sh`: `DistriCompute-<version>-windows-x64.zip` and
  `-linux-x64.tar.gz` with Eclipse Temurin 21.0.13.1+2 (pinned, SHA-256 checked), the application jar and a launcher
  (`DistriCompute.cmd`, `districompute.sh`) that keeps the data in `%LOCALAPPDATA%\DistriCompute\data` or
  `~/.local/share/districompute` and only opens the browser when DistriCompute is already running.
- **Tests:** the integration tests run against MySQL and, with the standalone profile, against H2;
  `scripts/standalone-smoke.sh` and `.ps1` test a package end to end without Java on the machine; the workflow
  `standalone.yml` runs them on Linux and on a Windows runner and publishes the tested packages for `v*` tags.
- Screenshots of every page in [docs/screenshots](docs/screenshots/).

### Fixed

- The fault lab's kernel-failure scenario failed half of the faulty worker's attempts at random, so about one run in
  sixteen showed no failure (seen once in the integration tests); every attempt on the faulty worker now fails and
  is retried elsewhere.
- Worker events arriving during shutdown logged an exception each; they are ignored now.
- `scripts/build.sh` cleans the backend first, so interface bundles of earlier builds no longer end up in the jar.

## 0.1.0-SNAPSHOT — DistriCompute-Application (branch `feature/application-gui`)

First complete version of the application that integrates DistriCompute-Core (`f269bf6`) and
DistriCompute-Distributed (`fc5f20e`) as pinned Git submodules. Not yet tagged as a release.

### Added

- **Build and integration:** one Maven reactor building Core, Distributed (with their own quality gates) and the
  backend; the interface built into the backend jar; `scripts/bootstrap.sh`, `scripts/build.sh`; two additive
  changes requested from and made in Distributed (result values for the application, worker deactivation and
  removal).
- **Backend (Spring Boot 4.1, Java 21, MySQL 8.4, Flyway):** experiments with repetitions, runs as Core tasks on the
  embedded Distributed coordinator in sequential, multithreaded and distributed modes; result values stored after a
  digest check; metrics from Core, Distributed and the application; the execution strategy advisor with estimate
  basis and confidence; runtime events stored and streamed (SSE with replay); worker records and administration;
  comparison and performance analysis; reports in PDF, CSV and JSON; templates and batches run in order; the fault
  lab on an isolated coordinator; settings; session authentication with CSRF protection, roles, rate and workload
  limits, login throttling, audit log. Migrations V1 (schema), V2 (fault lab), V3 (batch order).
- **Interface (React 19, Vite 8, TypeScript, Tailwind CSS 4, Recharts 3):** overview, new task (beginner and
  advanced levels, Core-generated forms, uploads, polynomials, templates), advisor panel, experiment page (live
  monitor with the assignment timeline per worker slot, results, metrics, events, configuration), experiments
  history, performance analysis, batches, workers, cluster topology, reports, fault lab, settings; light and dark
  themes; works at phone width.
- **Deployment:** Dockerfile (images for the application and the worker, unprivileged user, base images pinned by
  digest), Docker Compose with MySQL and scalable workers, `.env.example`, `scripts/compose-smoke.sh`.
- **Tests:** backend unit, persistence and integration tests against MySQL (Testcontainers) with worker nodes;
  frontend tests with recorded API responses; Playwright end-to-end tests against the packaged jar, MySQL and worker
  processes (including a worker killed and the backend crashed during runs); CI jobs for all of them, the Compose
  smoke test and the component suites.
- **Documentation:** architecture, plan, integration, API, database, advisor, metrics, analysis, reports, fault lab,
  batches and templates, testing, user guide, deployment, configuration, security, workers, fault tolerance,
  implementation report.

### Fixed during development (found by checking the running application or by CI)

- SSE streams now open immediately instead of with the first event.
- Core's violation paths are passed through unchanged, so messages appear next to their field.
- 401 and 403 (including a missing CSRF token) answer RFC 9457 problems.
- Speedups default to Core's execution time: wall time included waiting behind other runs on the coordinator's
  single local executor.
- A database deadlock between cancelling an experiment and starting its run (seen in CI) is prevented by
  serialising an experiment's state changes; transactions are retried after lock conflicts.
- Startup recovery no longer continues a batch while it marks interrupted experiments as failed.
- The configured session timeout is applied.

### Known limitations

See [docs/implementation-report.md](docs/implementation-report.md), section "Limitations".
