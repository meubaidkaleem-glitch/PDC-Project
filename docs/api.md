# REST API

Base path `/api/v1`, JSON (UTF-8). The running backend serves the generated OpenAPI document at `/v3/api-docs` and
an interactive explorer at `/swagger-ui.html` (both require a session). This page explains the conventions and
lists the endpoints. The OpenAPI document is the exact contract.

The API exposes its own records (`ExperimentSummary`, `RunView`, `AdviceView`, …), never JPA entities. Two
component objects appear inside them: Core's `WorkloadProfile` dimensions and Core's result `summary` values,
both as documented in Core's integration contract.

## 1. Conventions

**Authentication.** The browser signs in with `POST /api/v1/auth/login` (form fields `username`, `password`) and
receives a session cookie. Every other `/api/**` call needs that session. Without it the answer is `401` (never a
redirect). `GET /api/v1/auth/session` tells whether a session exists. `POST /api/v1/auth/logout` ends it.

**CSRF.** State-changing requests (`POST`, `PUT`, `PATCH`, `DELETE`) must send the value of the `XSRF-TOKEN`
cookie in the `X-XSRF-TOKEN` header. Requests without it get `403`. Any `GET` sets the cookie.

**Roles.** `USER` sees and manages their own experiments. `ADMIN` also sees everyone's, manages workers and users,
and uses the fault lab. Experiments of other users answer `404`, so their existence is not disclosed.

**Errors** are RFC 9457 Problem Details with two extra members:

```json
{
  "type": "https://districompute.org/problems/validation-failed",
  "title": "Bad Request",
  "status": 400,
  "detail": "Core rejected the parameters",
  "code": "VALIDATION_FAILED",
  "violations": [
    { "field": "parameters.rowsA", "code": "OUT_OF_RANGE", "message": "must be at least 1, got -3" }
  ]
}
```

| Status | `code` examples | When |
|---|---|---|
| 400 | `VALIDATION_FAILED`, `MALFORMED_REQUEST`, `INVALID_SORT`, `RAGGED_ROWS`, `NOT_A_NUMBER` | Invalid input. Core's violations are passed through with the field prefixed by `parameters.` |
| 401 | — | No session |
| 403 | `FORBIDDEN` | Missing role or CSRF token |
| 404 | `NOT_FOUND` | Unknown, or not visible to the user |
| 409 | `EXPERIMENT_ACTIVE`, `EXPERIMENT_FINISHED`, `USERNAME_TAKEN` | State conflict |
| 413 | `UPLOAD_TOO_LARGE` | Upload above `districompute.storage.max-upload-bytes` |
| 429 | `RATE_LIMITED` | Too many experiments per minute, or too many active experiments |
| 503 | `RESOURCE_EXHAUSTED` | The runtime refused the task (too many tasks) |
| 500 | `INTERNAL_ERROR` | Unexpected; details are only in the server log |

**Pagination.** List endpoints take `page` (from 0), `size` (1-100, default 20) and `sort` (`field,asc|desc`).
They answer `{ "items": [...], "page", "size", "totalItems", "totalPages" }`.

**Asynchronous work.** Creating an experiment answers `202 Accepted` at once. Execution happens in the background,
and its progress is visible through `GET /experiments/{id}`, `GET /experiments/{id}/live` and the event streams.

**Live events** use Server-Sent Events. Each event has `event: <type>` and `data: <EventView JSON>`. Stored
events also have `id: <n>`, and a reconnecting client sends `Last-Event-ID` to receive what it missed. Progress
events (`TASK_PROGRESS`, `ATTEMPT_PROGRESS`) are streamed but not stored, so they have no id.

## 2. Endpoints

### Authentication and accounts

| Method and path | Role | Purpose |
|---|---|---|
| `POST /auth/login` | — | Form login; answers the session view |
| `POST /auth/logout` | user | Ends the session (`204`) |
| `GET /auth/session` | — | `{authenticated, id, username, role}` |
| `POST /auth/password` | user | Change own password (`currentPassword`, `newPassword` of 12-128 characters) |
| `GET /users`, `POST /users`, `PATCH /users/{id}` | admin | List, create (`username`, `password`, `role`), change role, enable or disable, or reset a password |

### Catalog (Core)

| Method and path | Purpose |
|---|---|
| `GET /categories` | The six task categories with their algorithm counts; categories without Core algorithms have `available: false` |
| `GET /algorithms?category=` | Core's algorithms with parameter schemas, result type, verification method, limitations and every mode with `executable` and a reason |
| `GET /algorithms/{id}` | One algorithm |

### Task definition (nothing is executed)

| Method and path | Purpose |
|---|---|
| `POST /tasks/validate` | Application limits plus Core's validation; answers Core's workload analysis |
| `POST /tasks/analyze` | Validation plus the execution strategy advice (`AdviceView`, [advisor.md](advisor.md)) |
| `POST /inputs?algorithm=&parameter=` | Upload a `.csv` or `.txt` file of numbers for an array parameter (multipart field `file`) |
| `GET /inputs/{id}` | An upload's metadata (owner or admin) |
| `POST /expressions/polynomial` | Parse `{"expression": "3x^2 - 2x + 1", "variable": "x"}` into ascending coefficients |

A task (`TaskDraft`) is:

```json
{
  "algorithm": "matrix.multiply",
  "parameters": { "rowsA": 512, "colsA": 512, "colsB": 512, "seed": 7 },
  "inputs": { "matrixA": "<upload id>" },
  "execution": {
    "mode": "DISTRIBUTED_CPU", "repetitions": 3, "threads": null,
    "targetPartitions": null, "partitionsPerSlot": 2, "schedulingPolicy": "DYNAMIC",
    "maxWorkers": null, "maxAttempts": 3, "attemptTimeoutSeconds": 600,
    "timeoutSeconds": 1800, "maxMemoryMb": null, "priority": "NORMAL"
  }
}
```

Only `algorithm` and `execution.mode` are required. Settings that do not apply to the mode are ignored, for
example `threads` outside `MULTITHREADED_CPU`, or the scheduling settings outside `DISTRIBUTED_CPU`. Arrays with
more than 10 000 values must be uploaded.

### Experiments

| Method and path | Purpose |
|---|---|
| `POST /experiments` | `{name, description, task, overrideReason}`. Validates, records the current recommendation and whether it was followed, creates the runs, starts them in the background (`202`) |
| `GET /experiments` | Filters `search`, `status`, `algorithm`, `mode`, `category`, `from`, `to`, `batch`, `mine`; paged |
| `GET /experiments/{id}` | Detail: parameters, input summary, configuration, recommendation, runs, results, metric statistics |
| `GET /experiments/{id}/metrics` | Every metric value of every run ([metrics.md](metrics.md)) |
| `GET /experiments/{id}/live?run=` | The runtime's view of the active or latest run: partitions, attempts, workers, scheduler explanations, metrics so far |
| `GET /experiments/{id}/events?after=&limit=` | Stored events, oldest first |
| `GET /experiments/{id}/stream` | Live events of the experiment (SSE) |
| `POST /experiments/{id}/cancel` | Cancel (`202`; `409` if already ended) |
| `POST /experiments/{id}/rerun` | New experiment with the same configuration (`202`) |
| `DELETE /experiments/{id}` | Delete a finished experiment with its runs, events and stored results (`204`) |
| `GET /experiments/{id}/runs/{n}/result` | Result of run `n`: Core's summary and digest, validation, storage |
| `GET /experiments/{id}/runs/{n}/result/preview?rows=&columns=` | First rows and columns of a stored CSV result, or a small JSON result |
| `GET /experiments/{id}/runs/{n}/result/download` | The full stored value (CSV or JSON file) |

### Tasks (one run each)

A task is one run, identified by Core's task id `<experimentId>-r<n>`.

| Method and path | Purpose |
|---|---|
| `POST /tasks` | `{name, task, overrideReason}`: creates a single-run experiment; answers `{taskId, experimentId}` |
| `GET /tasks/{taskId}` | The run record and its live view |
| `POST /tasks/{taskId}/cancel` | Cancel the task's experiment |
| `GET /tasks/{taskId}/result` | The run's result |

### Workers (Distributed)

| Method and path | Role | Purpose |
|---|---|---|
| `GET /workers` | user | Connected workers (live) and the application's records, with the capabilities workers reported |
| `GET /workers/{id}` | user | One worker |
| `GET /workers/{id}/events` | user | The worker's event history |
| `GET /workers/{id}/partitions` | user | Partitions it delivered |
| `GET /workers/{id}/registration` | user | How to start a worker with this id (no secret) |
| `POST /workers/{id}/test` | user | Connection test: worker port open and heartbeat on time |
| `POST /workers/register` | admin | Register and approve an expected worker; answers start instructions |
| `POST /workers/{id}/approve` | admin | Approve a worker (relevant with `districompute.workers.require-approval`) |
| `POST /workers/{id}/deactivate`, `/activate` | admin | Stop or resume scheduling on a worker (`{reason}`) |
| `DELETE /workers/{id}?reason=` | admin | Stop a connected worker and forget it |

### Events and system

| Method and path | Purpose |
|---|---|
| `GET /events?limit=` | Recent events visible to the user |
| `GET /events/stream` | Live events visible to the user (SSE) |
| `GET /system/info` | Versions of the application, Core, Distributed and the worker protocol (public) |
| `GET /system/status` | Coordinator, workers, local resources, GPU status, health checks |
| `GET /dashboard` | Overview: experiment counts, recent experiments and events, worker summary, measured performance per mode, health |

### Performance analysis

| Method and path | Purpose |
|---|---|
| `POST /analysis/compare` | Compare 2 to 20 experiments the user may see: `{experimentIds, baselineId?, basis?}` with `basis` `EXECUTION_TIME` (default when every experiment reports it) or `WALL_TIME`. Answers statistics per experiment, speedup and efficiency against the baseline, a time breakdown, further measurements, compatibility issues (`ERROR`, `WARNING`, `INFO` with a stable `code`) and plain-language findings. See [analysis.md](analysis.md) |
| `GET /analysis/workloads` | Finished experiments with completed runs, grouped by algorithm, version and dimensions, newest first |

Errors: `DUPLICATE_EXPERIMENT`, `BASELINE_NOT_COMPARED` (400), validation (400), an experiment the user may not
see (404).

### Reports

| Method and path | Purpose |
|---|---|
| `GET /experiments/{id}/report?format=pdf\|csv\|json` | Report of one experiment |
| `GET /analysis/report?ids=&baseline=&basis=&format=pdf\|csv\|json` | Report of a comparison |
| `GET /experiments/export?format=csv\|json&<history filters>` | The experiment history, at most 5,000 rows |

Downloads (`Content-Disposition: attachment`, `Cache-Control: no-store`), audited; see [reports.md](reports.md).

### Fault lab (administrators)

| Method and path | Purpose |
|---|---|
| `GET /fault-lab` | Whether the lab is enabled and busy, its scenarios and run setup |
| `POST /fault-lab/runs` | Run `{scenario, seed?}` on an isolated coordinator in the background (202); 409 `FAULT_LAB_DISABLED` or `FAULT_LAB_BUSY` |
| `GET /fault-lab/runs?limit=`, `GET /fault-lab/runs/{id}` | Lab runs with their outcome (detection, recovery, result) |
| `GET /fault-lab/runs/{id}/events` | The run's runtime events, all marked `simulated` |

See [fault-lab.md](fault-lab.md).

### Templates and batches

| Method and path | Purpose |
|---|---|
| `GET /templates`, `POST /templates`, `POST /templates/from-experiment/{id}` | List and save the user's templates; Core validates the task first |
| `GET /templates/{id}`, `DELETE /templates/{id}` | One template |
| `GET /experiments/{id}/task` | The task of an experiment, as it would be submitted again |
| `POST /batches` | Submit experiments that run one after another; nothing is created unless every item is valid (`items[i].` violations) |
| `GET /batches`, `GET /batches/{id}`, `POST /batches/{id}/cancel` | Batches with their experiments in order; cancellation |

See [batches-and-templates.md](batches-and-templates.md).

Endpoints added in later phases (settings) are listed here when they are implemented.
