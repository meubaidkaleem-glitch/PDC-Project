# Database

MySQL 8.4 (InnoDB, `utf8mb4`). The schema belongs to Flyway: migrations live in
[database/migrations](../database/migrations), are packaged into the backend jar and run at startup. Hibernate only
validates the mapping (`ddl-auto: validate`), so a mismatch stops the application instead of changing tables.
Times are `DATETIME(3)` in UTC (MySQL's `TIMESTAMP` ends in 2038).

## 1. Tables

```
app_users ─┬─< experiments ─┬─< experiment_runs ─┬─< tasks              (one row per partition)
           │       │        │                    ├─< run_metrics        (one row per metric)
           │       │        │                    └── experiment_results (one per run)
           │       │        └─< execution_events (runtime and application events)
           │       └── batches
           ├─< batches
           └─< experiment_templates
workers            (one row per worker id; history, approval, deactivation)
app_settings       (non-secret settings changed at run time)
audit_events       (logins, submissions, worker and settings changes)
```

| Table | Purpose | Key constraints and indexes |
|---|---|---|
| `app_users` | Accounts: user name, BCrypt hash, role (`ADMIN`, `USER`), enabled | Unique user name; role check |
| `experiments` | Algorithm, version, parameters (JSON, without uploaded arrays), input source and summary, execution mode, recommended mode and whether it was followed, override reason, advice basis, scheduling policy, configuration (JSON), repetitions, status, timestamps, error | UUID key (also Core's `experimentId`); status, input and repetition checks; indexes on owner+time, status, algorithm+time, batch |
| `experiment_runs` | One Core task per repetition: Core task id `<experimentId>-r<n>`, status, threads, partitions, workers used, wall time, digest, verification, error | Unique (experiment, index) and Core task id; cascades from the experiment |
| `tasks` | Partitions of a run: Core partition id, worker that delivered it, attempt count, times, error | Unique (run, partition index); index on worker |
| `run_metrics` | Name, value, unit and source (`APPLICATION`, `CORE`, `RUNTIME`, `DERIVED`) of each measurement | Unique (run, name, source); index on experiment+name |
| `experiment_results` | Core's result type, summary (JSON) and digest; validation status and method; storage reference, format, size; whether the stored value's digest matches | One per run; validation and format checks |
| `execution_events` | Runtime events (with the runtime's sequence number) and application events: type, severity, message, details (JSON), simulated flag | Indexes on experiment+id, worker+id, time |
| `workers` | Host name, endpoint, reported capabilities (JSON), last status and reason, last heartbeat, first registration, last seen, approval, deactivation, notes, counters | Worker id key |
| `batches`, `experiment_templates` | Grouped submissions; reusable configurations | Unique template name per owner |
| `app_settings` | Run-time settings (never secrets) | Key |
| `audit_events` | Who did what, when, from where | Indexes on time, user+time |

Deleting an experiment cascades to its runs, partitions, metrics, results and events. The application also
deletes the experiment's result files.

## 2. What is not stored in tables

* **Results** are files in `<dataDir>/results/<experimentId>/run-<n>.csv|json`. `experiment_results.storage_ref`
  points to them. Before writing a value, the application recomputes its digest with Core and compares it with the
  verified digest (`stored_digest_match`). Values larger than `districompute.storage.max-result-bytes` keep only
  Core's summary.
* **Uploaded inputs** are files in `<dataDir>/inputs/<id>.bin` (parsed numbers), with metadata in
  `<id>.json`: owner, parameter, shape, original name, size, SHA-256. Experiments refer to them by id in their
  parameters.
* **Secrets** (cluster secret, database password, initial administrator password) come from environment variables
  or files and are never stored in the database. Passwords are stored only as BCrypt hashes.

## 3. Migrations and tests

* `V1__initial_schema.sql` creates the schema above. Later changes get new versioned files; applied migrations are
  never edited.
* `PersistenceTest` runs the migrations on a real MySQL 8.4 (Testcontainers) and checks Hibernate's validation,
  round trips of every entity, cascades, and the unique, check, JSON and foreign-key constraints.
