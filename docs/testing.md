# Testing

What is tested, how to run it, and what is not covered yet. Section 2 of
[implementation-plan.md](implementation-plan.md) records which phases are done.

## 1. Suites

| Suite | Where | Needs | Command |
| --- | --- | --- | --- |
| Backend unit tests | `backend/src/test/java/.../task` and others without a tag | JDK 21 | `./mvnw -pl backend test` |
| Persistence tests | `PersistenceTest` | Docker (MySQL 8.4 in Testcontainers) | part of `./mvnw -pl backend verify` |
| Backend integration tests | `ApplicationIntegrationTest` (`@Tag("integration")`) | Docker | part of `./mvnw -pl backend verify` |
| Frontend unit tests | `frontend/src/**/*.test.ts(x)` | Node.js 22 | `cd frontend && npm test` |
| Component suites | Core and Distributed, at the pinned commits | JDK 21 | `./mvnw -pl components/core,components/distributed/distributed install` |
| End-to-end tests | not written yet (Phase 15) | | |

Tests tagged `e2e` are excluded from the default backend run (`test.excludedGroups` in `backend/pom.xml`); none
exist yet.

Before the backend suites, install the components once: `./mvnw -B -ntp install -pl
components/core,components/distributed/distributed -DskipTests` (or run `scripts/build.sh`).

## 2. Backend integration tests

`ApplicationIntegrationTest` starts the whole application on a random port against MySQL 8.4 (Testcontainers),
with the embedded coordinator on a loopback port and **two Distributed `WorkerNode`s in the same JVM** that connect
to it over TCP. It drives the application only through HTTP, as the browser does: form login, CSRF token, JSON
API, multipart upload, Server-Sent Events. It covers:

- anonymous access (401 problem), missing CSRF token (403 problem), public version information;
- the OpenAPI document;
- the catalog (categories without Core algorithms are reported as unavailable);
- Core's validation errors, with the field path the form uses (`parameters.rowsA`);
- the advisor (every mode explained, the cluster's resources);
- matrix multiplication in sequential, multithreaded and distributed mode: all three produce the same result
  digest; preview, download, events and the live view;
- uploaded matrices added exactly;
- the polynomial parser endpoint;
- visibility between users (another user's experiment is 404);
- worker administration (deactivate, activate, remove; forbidden for users);
- cancellation;
- the SSE stream (named events with ids for resumption).

The worker processes of a real deployment are separate JVMs; the end-to-end tests of Phase 15 will start them as
processes.

## 3. Frontend tests

Vitest with Testing Library in jsdom. The tests render the real application (routes, session check, shell) and
replace the network with a fake backend (`src/test/server.ts`) that answers with **responses recorded from a
running backend** (`src/test/fixtures`, see its README). `EventSource` is replaced by a fake that a test can
open and push events through. Covered:

- sign-in (wrong and right password, CSRF header, form fields), session expiry, administrator-only navigation,
  sections of unfinished phases marked "Not implemented yet";
- overview: figures from the API, stream state, reload on a finished experiment, error with retry;
- new task: categories Core implements, beginner form fields, analysis request body, advice display, submission
  with the chosen mode, Core's violations next to their field, syntax errors blocking analysis, GPU and hybrid
  listed as unavailable;
- advisor panel: unreliable advice is not presented as a recommendation, reliable advice with basis and estimate,
  override selection, unavailable modes disabled;
- experiment: runs, results with download links, metrics with units, configuration with Core defaults marked,
  live events from the stream, reload when a run finishes, cancellation, not-found;
- history: filters and sorting sent to the API, comparison selection, empty state;
- workers: reported capabilities, deactivation with a reason (administrator);
- assignment timeline lanes; formatting of durations, sizes and metrics.

The fixtures are never part of the build: production code imports nothing under `src/test`.

## 4. Manual check against the real stack

The interface was also exercised in Chromium (Playwright, headless) against the packaged jar, MySQL 8.4 in Docker
and two worker processes started from the Distributed jar: sign-in, a matrix multiplication in distributed mode
with three repetitions, the live monitor, results, metrics, configuration, workers, topology, dark theme and a
390-pixel-wide viewport. This is not an automated test; Phase 15 turns this journey into Playwright tests.

## 5. CI

`.github/workflows/application-ci.yml`:

1. `frontend`: `npm ci`, typecheck, tests, build; the built files are passed on.
2. `build-and-test`: verifies the submodule pins, builds Core and Distributed with their own quality gates, then
   `./mvnw verify -pl backend -Pwith-frontend` (unit, persistence and integration tests; Docker is available on
   the runner) and checks that the jar contains the interface.
3. `component-tests`: the Core and Distributed suites at the pinned commits.
