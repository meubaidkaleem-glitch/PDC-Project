# Implementation report — DistriCompute-Application

Contributor 3's component: the web application and the integration of DistriCompute-Core and
DistriCompute-Distributed, on branch `feature/application-gui` of
<https://github.com/meubaidkaleem-glitch/PDC-Project>. This report states what was built, what was tested and
how, and what is limited. Every result quoted here was produced while writing it; nothing is projected.

## 1. Integration status

| Component | Path | Pinned commit | Branch | How it is used |
|---|---|---|---|---|
| DistriCompute-Core | `components/core` | `f269bf68e735df4cadf4e573792853b86efb2705` | `feature/core-engine` | Algorithm registry and schemas, validation, workload analysis, local execution, verification, results and digests |
| DistriCompute-Distributed | `components/distributed` | `fc5f20e487d341c4ebef45edf205056b091a71ee` | `feature/distributed-engine` | Embedded coordinator (all modes run through `Coordinator.submit`), advice, events, worker registry and administration; worker processes from its jar |

Both are Git submodules built by the root Maven reactor with their own quality gates and unchanged POMs. The
application contains no copy of their source and no reimplementation of their algorithms or networking. Two
additive changes were requested from Distributed, made in its own branch with tests and documentation, and pinned
here ([integration.md](integration.md) §4): delivery of result values to the application (`ResultSink`) and worker
deactivation, activation and removal. Core needed no change.

## 2. What was built

### Completed

| Area | Content | Documents |
|---|---|---|
| Build | Reactor, Maven wrapper, `scripts/bootstrap.sh` (pins checked), `scripts/build.sh`, interface packaged in the jar | [integration.md](integration.md) |
| Database | MySQL 8.4, Flyway migrations V1–V3, constraints checked against a real MySQL | [database.md](database.md) |
| API | REST under `/api/v1`, RFC 9457 errors with codes and field violations, paging, OpenAPI, SSE | [api.md](api.md) |
| Tasks | Catalog from Core in six categories (two without Core algorithms shown as unavailable), forms generated from Core's schemas, generated data, pasted data, CSV/TXT uploads, restricted polynomial parser | [user-guide.md](user-guide.md) |
| Execution | Sequential, multithreaded and distributed runs; repetitions; cancellation; result values stored after a digest check; metrics from Core, Distributed and the application; recovery after a restart | [metrics.md](metrics.md), [fault-tolerance.md](fault-tolerance.md) |
| Advisor | Recommendation, alternatives with verdicts and reasons, estimate basis (measured, calibrated, theoretical, none), confidence, resources, warnings, override reason recorded | [advisor.md](advisor.md) |
| Monitoring | Runtime events stored in batches and streamed with replay; live view of partitions and attempts; assignment timeline per worker slot | — |
| Workers | Records synchronised from the coordinator, capabilities as reported, approval policy, deactivation, removal, connection test, start instructions, topology | [workers.md](workers.md) |
| Analysis | Comparison with compatibility checks (stable codes), statistics, speedup and range, efficiency, time breakdown, findings; basis Core execution time or wall time | [analysis.md](analysis.md) |
| Reports | Experiment and comparison reports as PDF, CSV and JSON; history export; audited | [reports.md](reports.md) |
| Fault lab | Eight scenarios on an isolated coordinator per run with Distributed's fault profiles, detection and recovery reported, events marked simulated | [fault-lab.md](fault-lab.md) |
| Batches, templates | Batches run in order, all-or-nothing validation, benchmark builder; validated personal templates | [batches-and-templates.md](batches-and-templates.md) |
| Interface | Pages A–J of the specification: overview, new task, advisor, live monitor, workers, topology, performance analysis, history, reports, settings; plus batches and the fault lab; beginner and advanced levels; light and dark themes; phone width | [user-guide.md](user-guide.md) |
| Security | Session login with CSRF protection, roles, BCrypt, login throttling, rate and workload limits, owner-only access with 404, secrets from environment or owner-only files, audit log, CSP | [security.md](security.md) |
| Deployment | Images for the application and the worker, Docker Compose with MySQL and scalable workers, smoke test | [deployment.md](deployment.md), [configuration.md](configuration.md) |
| Standalone packages | Windows and Linux packages with a bundled Java runtime, embedded H2 database and two local workers: unzip and double-click, nothing to install; built, tested without Java and released by CI | [standalone.md](standalone.md) |

### Partial or limited

| Item | State |
|---|---|
| Task categories | Matrix, numerical and data-processing algorithms are available because Core implements them (10 algorithms). Image/signal processing and custom simulations are listed as unavailable: Core has no such algorithms, and the application does not implement computations itself. Monte Carlo estimation is available under numerical methods |
| Execution modes | GPU and hybrid execution are shown as unavailable with the components' reasons; no component implements them |
| Resuming runs | Runs interrupted by a restart of the application are marked failed and can be run again; they are not resumed (the coordinator's state is in memory) |
| Fault lab | Workers are threads of the application process; operating-system process crashes are covered by the end-to-end tests instead |
| Docker images | Package the jars built by `scripts/build.sh`; there is no from-source Docker build |

### Not implemented

Password reset by e-mail, multi-factor authentication, single sign-on, HTTPS termination inside the application
(use a reverse proxy), encryption of result files at rest, high availability of the coordinator, arbitrary user code.

## 3. Tests executed

All of the following were run on 2026-11-24 on a 4-core Linux machine with JDK 21, Node.js 22 and Docker 29.

| Suite | Command | Result |
|---|---|---|
| Backend unit, persistence and integration tests (two worker nodes over TCP, fault lab scenarios; the integration tests once against MySQL 8.4 in Testcontainers and once against H2 with the standalone profile) | `./mvnw verify -pl backend -Pwith-frontend` | 76 tests, 0 failures (20 integration on MySQL, 21 on H2, 3 persistence, 32 unit); fault lab tests repeated three times on both databases after the fix of defect 10 |
| Frontend tests (Vitest, recorded API responses) | `npm test` in `frontend` | 59 tests in 16 files, 0 failures; typecheck and build pass |
| End-to-end tests (Playwright, packaged jar, MySQL in Docker, worker processes) | `scripts/e2e.sh` | 4 tests, 0 failures (42 s): journey to CSV and PDF downloads, benchmark batch with comparison report, worker process killed during a run, backend killed during a run |
| Docker Compose smoke test | `scripts/compose-smoke.sh` | Passed: application healthy, two workers registered, distributed experiment verified |
| Windows package test (2026-11-25, CI run [45581615861](https://github.com/meubaidkaleem-glitch/PDC-Project/actions/runs/45581615861), `windows-2025` runner) | `scripts/standalone-smoke.ps1` with Java removed from `PATH` and `JAVA_HOME` | Passed: ready after 14 s, listening only on 127.0.0.1, printed password accepted, two local workers, distributed experiment verified, second start reported the running instance, data kept across a forced stop and restart (ready after 13 s), no error in the console |
| Linux package test (2026-11-25), launcher in an `ubuntu:24.04` container without Java or curl | `STANDALONE_SMOKE_IMAGE=ubuntu:24.04 scripts/standalone-smoke.sh dist/…-linux-x64.tar.gz` | Passed: initial password printed and accepted, two local workers, distributed experiment verified, second start reports the running instance, data kept across a restart, no error in the console |
| Stress check of cancellation | 25 batches cancelled right after creation, against MySQL | All answered 202, no deadlock, no run submitted after cancellation |
| Fault lab, all scenarios, seed 7 | Through the API and in the integration tests | Six `RECOVERED`, two `UNAFFECTED`, every result equal to the fault-free reference |
| Browser checks | Chromium against the packaged jar, MySQL and two worker processes | Every page in light and dark themes and at 390 px width; PDF reports rendered and inspected |

CI (`.github/workflows/application-ci.yml`) runs the frontend job, the backend job (with the interface in the jar),
the end-to-end job, the Compose smoke test and the component suites; section 6 records the run for the final
commit. Before it, CI was green for every pushed commit except two backend-job runs: `2c5f303`, where the simulated-restart
test assumed the first experiment of a batch could not finish before recovery reached it (true on the development
machine, not on the faster CI machine; the test now checks only what it is about), and `346e993`, where a MySQL
deadlock the CI machine exposed failed a cancellation (section 4, fixed in `a1dda6a`).

The standalone packages workflow failed three times before it passed, each time in the tests rather than in the
packages: `3000f80` (the Linux test passed but could not delete files the container had written as root),
`5ef5f3c` (the Windows test miscounted the workers: PowerShell passed the JSON array on as one object) and
`b8754fe` (the Windows test's second start waited for the browser the launcher opened, until the job timed out).
Application CI then failed once on `232150a`, a documentation commit: a frontend test looked for the live stream as
soon as the page heading appeared, while the page opens it in an effect just after; the test now waits for it.

## 4. Defects found and fixed

Found by running the application, by its tests or by CI, and fixed with a test where possible:

1. SSE streams were not committed until the first event, so pages reported "connecting" (fixed: immediate comment).
2. Core's violation paths were prefixed twice, so messages could not be placed next to fields.
3. 401 and 403 from the security filters were not RFC 9457 problems.
4. Wall-time speedups were misleading: the coordinator runs local tasks one after another, so runs submitted
   together waited for each other (about 600 ms of 900 ms in a measured case). Speedups now default to Core's
   execution time, wall time warns about waiting, batches run experiments in sequence.
5. The fault lab's "recovered partitions" compared with the wrong status name.
6. Startup recovery could continue a batch while marking its experiments failed (a test fails without the fix).
7. A MySQL deadlock between cancelling an experiment and starting its run, seen only in CI, answered 500 and could
   submit a run after cancellation (fixed by serialising an experiment's state changes and retrying lock conflicts;
   stress-checked).
8. The configured session timeout was not applied; failed sign-ins were not throttled.
9. Several interface defects seen in the browser: wrong beginner fields, a repeated advisor message, overlapping
   timeline bars, phone-width overflow, a stranded chart caption in PDFs, an event timeline that missed the last
   events of a lab run.
10. The fault lab's kernel-failure scenario chose failing attempts at random, so about one run in sixteen showed no
    failure and the integration test failed (seen once); every attempt on the faulty worker now fails.
11. Worker events arriving during shutdown were rejected by the stopped executor and logged as exceptions.
12. In the standalone mode H2 closed the database in its own shutdown hook before the application had stopped
    (found by the package test's console check).

Known open issues: none that make a result wrong or a page fail. Limitations are in section 5.

## 5. Limitations and risks

- **Measurements on shared machines.** Local runs share the application's processors with everything else in the
  process; CPU utilisation is process-wide; distributed runs on workers that share the machine compete with it.
  The analysis states these caveats (`CONCURRENT_RUNS`, `WAITING_INCLUDED`, `UNCONTROLLED_LOAD`); batches reduce
  them.
- **Single local executor.** Local runs of concurrent experiments queue on the coordinator's single local thread
  (a property of Distributed). Throughput of many concurrent local experiments is limited accordingly.
- **No resumption after restart**, one application instance, coordinator state in memory.
- **Login throttling** is in memory and per user name and address.
- **PDF text** uses the standard fonts: characters outside Windows-1252 become `?` (CSV and JSON keep them).
- **Interface bundle** is one 854 kB JavaScript file (248 kB compressed); no code splitting.
- **Security in production** depends on the operator adding HTTPS, keeping the worker port private or using TLS,
  and protecting the secrets ([security.md](security.md) §8).
- **Standalone packages** run everything on one computer: their distributed runs use two workers on the same
  machine (a demonstration of the mechanism, not a cluster measurement), and they are not code-signed, so Windows
  SmartScreen asks for confirmation at the first start ([standalone.md](standalone.md)).
- **Component versions** are snapshots (`0.1.0-SNAPSHOT`); updating a component follows [integration.md](integration.md)
  §5 and must rerun all suites.

## 6. Build, deploy, verify

```bash
git clone --recurse-submodules --branch feature/application-gui https://github.com/meubaidkaleem-glitch/PDC-Project.git districompute
```

```bash
cd districompute && scripts/build.sh
```

```bash
scripts/e2e.sh
```

```bash
cp .env.example .env && docker compose up -d --build
```

```bash
scripts/package-standalone.sh --version 0.1.0 --skip-build
```

[deployment.md](deployment.md) describes running without Docker and operations.

**Release v0.1.0** (2026-11-25): the standalone workflow, run by hand with version `0.1.0` on `146a89d` (run
[45673976023](https://github.com/meubaidkaleem-glitch/PDC-Project/actions/runs/45673976023)), built both packages,
passed the Linux test and the Windows test (no Java; ready after 12 s; listening only on 127.0.0.1; verified
distributed experiment; data kept across a forced restart) and published
[v0.1.0](https://github.com/meubaidkaleem-glitch/PDC-Project/releases/tag/v0.1.0) with
`DistriCompute-windows-x64.zip` (116,671,739 bytes), `DistriCompute-linux-x64.tar.gz` (120,247,704 bytes) and
`SHA256SUMS`. Afterwards the three files were downloaded through the `releases/latest/download` links of the README
on `main`, both checksums matched, and the downloaded Linux package passed `scripts/standalone-smoke.sh` again in an
`ubuntu:24.04` container without Java.

CI for commit `2c5ae67` (the last change to code, configuration and tests): run
[45407664972](https://github.com/meubaidkaleem-glitch/PDC-Project/actions/runs/45407664972) passed all five jobs:
frontend; backend with the interface in the jar; end-to-end tests with MySQL and worker processes; Docker images and
the Compose smoke test; Core and Distributed suites at the pinned commits.
