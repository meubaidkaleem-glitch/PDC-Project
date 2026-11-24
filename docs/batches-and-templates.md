# Batches and templates

## 1. Templates

A template is a saved task: algorithm, parameters, uploaded inputs (by reference) and execution settings. Templates
are personal: only their owner sees and uses them.

- **Saving** (`POST /api/v1/templates`) validates the task with DistriCompute-Core first, exactly as a submission
  would, so a template is always runnable. Names are unique per user (409 `TEMPLATE_NAME_TAKEN`); a user keeps at
  most 200 templates.
- **From an experiment** (`POST /api/v1/templates/from-experiment/{id}`): the task of an experiment the user may see.
- **Using** one: "Start from a template" on the New task page fills in the form (uploaded inputs included, if they
  still exist); the Batches page can start a batch from a template.
- Saving and deleting are audited.

Uploaded inputs are referenced, not copied: a template that refers to an upload the owner no longer has fails
Core's validation when it is used, and the form asks for the data again.

## 2. Batches

A batch is a list of experiments submitted together and **run one after another, in the given order**.

- **All or nothing.** Every item is validated before anything is created. If any item is invalid, the answer is 400
  with each violation prefixed by its item (`items[1].parameters.rowsA`) and nothing is submitted.
- **Limits.** A batch holds at most `districompute.limits.max-batch-size` experiments (50). It counts as **one**
  submission for the rate limit and as **one** active experiment for the per-user limit, because it runs one
  experiment at a time.
- **Order.** Experiments store their position (`experiments.batch_position`, migration `V3`). When an experiment of
  a batch finishes, for whatever reason, the application starts the next queued one, unless another experiment of
  the batch is still running. Starting is idempotent and the hand-over is synchronised, so one experiment of a batch
  runs at a time.
- **Why sequential.** Experiments that run at the same time compete for processors and workers, and their times
  include waiting for each other ([analysis.md](analysis.md)). A batch avoids both, which makes it the way to
  benchmark one task in several modes.
- **Cancelling** (`POST /api/v1/batches/{id}/cancel`) cancels the queued experiments first, then the running one;
  completed experiments keep their results.
- **Status** is derived from the experiments: `QUEUED`, `RUNNING`, then `FAILED` if one failed, `CANCELLED` if one
  was cancelled, else `COMPLETED`.
- A restart marks queued and running experiments as failed (interrupted), as for any experiment; the rest of the
  batch does not resume.

### Benchmark batches in the interface

The Batches page builds a batch from a completed experiment or a template: the same task in each chosen mode
(sequential, parallel with one or more thread counts, distributed), with the same number of repetitions. Settings
that do not apply to a mode are left out (thread counts outside parallel runs, scheduling settings outside
distributed runs, partitioning in sequential runs), so every experiment is valid on its own. The batch page shows
progress, links to the comparison of its completed experiments and offers their comparison report.

Measured on a 4-core machine with two local workers (768 × 768 matrix multiplication, 3 repetitions each, run as a
batch): Core execution time 445 ms sequentially, 276 ms on 2 threads (1.62×), 158 ms on 4 threads (2.82×) and
715 ms distributed (workers on the same machine), with almost no waiting time before each run.

## 3. API

| Method and path | Purpose |
|---|---|
| `GET /templates` | The user's templates, by name |
| `POST /templates` | Save `{name, description?, task}` (201) |
| `POST /templates/from-experiment/{id}` | Save the task of an experiment `{name, description?}` (201) |
| `GET /templates/{id}`, `DELETE /templates/{id}` | Read or delete one of the user's templates |
| `GET /experiments/{id}/task` | The task an experiment ran, as it would be submitted again |
| `POST /batches` | Submit `{name, items: [{name, description?, task, overrideReason?}]}` (202) |
| `GET /batches?limit=` | The user's batches (all for administrators), newest first |
| `GET /batches/{id}` | A batch with its experiments in order |
| `POST /batches/{id}/cancel` | Cancel what has not finished (202) |
