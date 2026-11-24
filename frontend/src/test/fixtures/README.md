# Test fixtures

Responses recorded from a running DistriCompute backend (MySQL, two worker processes) on 2026-11-24, used **only by the
frontend unit tests**. They are never bundled into the application: nothing under `src/test` is imported by
production code, and the production build does not include it.

| File | Endpoint |
| --- | --- |
| `session.json` | `GET /api/v1/auth/session` (administrator) |
| `categories.json` | `GET /api/v1/categories` |
| `algorithms.json` | `GET /api/v1/algorithms` |
| `advice.json` | `POST /api/v1/tasks/analyze` (matrix multiplication 64 × 64 × 64) |
| `dashboard.json` | `GET /api/v1/dashboard` |
| `experiments.json` | `GET /api/v1/experiments` |
| `experiment.json`, `live.json` | `GET /api/v1/experiments/{id}` and `/live` (distributed run, 3 repetitions) |
| `events.json` | first 24 events of `GET /api/v1/experiments/{id}/events` |
| `workers.json` | `GET /api/v1/workers` |

To refresh them, record the same endpoints again from a running stack; tests that depend on specific values say so.
