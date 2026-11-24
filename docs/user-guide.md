# User guide

DistriCompute runs computations (matrix operations, numerical methods, data processing) sequentially, on several
threads, or on worker machines, measures them, and compares the strategies. Everything the interface shows comes
from the server: measured values, the runtime's events, Core's analysis. Estimates are always labelled as such.

## 1. Signing in and finding your way

Sign in with the account an administrator gave you. The left menu has every section; the top bar switches between
**Beginner** (recommended settings and explanations) and **Advanced** (every setting), the theme, and signing out.
The level and theme are kept in your browser; Settings has them too, with your password.

**Overview** shows your experiments by status, connected workers and free slots, local processors, system health,
recent activity and the median times measured per mode. It updates live.

## 2. Running a task

1. **New task** → choose a category, then an algorithm. Categories without an algorithm in DistriCompute-Core are
   shown as not available.
2. **Input and parameters.** Generated data needs only its size (and optionally a seed for reproducibility). "Use my
   data" lets you paste numbers or upload CSV or text files; matrix sizes are filled in from the file. Polynomials
   are typed as expressions (`3x^2 - 2x + 1`) and parsed, never executed. Core checks everything; problems appear
   next to the field.
3. **Execution.** Choose the mode: sequential, parallel (threads) or distributed (workers). GPU and hybrid execution
   are listed as not available because no component implements them. Advanced mode adds threads, partitions,
   scheduling policy, attempts and timeouts, a memory cap and **repetitions** (several runs of the same task, for
   reliable medians).
4. **Analyse and recommend** asks the advisor (section 3). You may follow its recommendation or not; if you don't,
   you are asked why, and the reason is recorded with the experiment.
5. **Run.** The experiment page opens and shows the run live.

"Start from a template" fills the form with a saved task; "Save as template" keeps the current one for later.

## 3. The execution strategy advisor

For each mode the advisor shows a verdict (recommended, suitable, unsupported), an estimated time with its basis,
the median measured on this platform for the same workload if there is one, and its reasons; then Core's analysis of
the workload (size, work, memory, how it can be split) and the resources it considered (processors, workers, slots,
network). The **basis** says where a figure comes from: *measured* (completed runs of the same workload),
*calibrated estimate* (Core's model with measured throughput), *theoretical estimate* (Core's model alone) or *no
estimate*. With nothing to estimate from, the advisor says that a reliable recommendation cannot be made yet and
marks its structural preference as unconfirmed.

## 4. Watching and reading an experiment

- **Live monitor:** partitions completed, running, pending and failed, attempts and retries, the data moved, active
  workers, and the assignment timeline: one row per worker slot, one bar per attempt (hover for the scheduler's
  reason).
- **Results:** Core's verification (method and outcome), the digest, the stored value with a preview and a download
  (CSV for matrices and arrays, JSON otherwise).
- **Metrics:** wall time per run and statistics over completed runs for every measurement (median, minimum, maximum,
  standard deviation). With one run the variation is unknown; repetitions show it.
- **Events:** the runtime's and the application's events, oldest first.
- **Configuration:** the mode, settings, recommendation, parameters (Core's defaults marked) and workload.

Actions: cancel a running experiment, run it again, copy it into a new task, download its report, delete it.

## 5. Comparing and benchmarking

- **Experiments** (history) filters and sorts everything you can see; select two or more to compare them.
- **Performance analysis** compares experiments of the same workload: median time, range, variation, **speedup**
  against a baseline (a sequential experiment by default) and **efficiency** per thread or per worker, a breakdown of
  where the time goes, and plain-language findings. It compares by **Core execution time** by default; wall time
  also counts waiting for other runs, which the page warns about. Issues such as different sizes, single runs, high
  variation or runs that competed with others are listed above the figures ([analysis.md](analysis.md)).
- **Batches** run one task in several modes and thread counts, one experiment after another so they do not compete,
  with the same repetitions. The batch page links to the comparison of its completed experiments
  ([batches-and-templates.md](batches-and-templates.md)).

## 6. Reports

**Reports** downloads an experiment report, a comparison report (per workload group, or from Performance
analysis with your own selection) or the experiment history, as PDF (to read and print), CSV (spreadsheets) or JSON
(programs) ([reports.md](reports.md)).

## 7. Workers and topology

**Workers** lists the worker processes with what they reported, their status and load. Administrators register,
approve, deactivate, activate and remove workers and test connections ([workers.md](workers.md)). **Cluster
topology** draws the coordinator and its workers.

## 8. For administrators

- **Settings:** the cluster's default scheduling policy, the limits and server configuration, and users (create,
  change role, enable or disable).
- **Fault lab:** inject a fault into a simulated worker on an isolated coordinator and see how it is detected and
  recovered ([fault-lab.md](fault-lab.md)); available when the server enables it.
- Administrators see every user's experiments.

## 9. Limits you may meet

The server limits active experiments, submissions per minute, repetitions, batch size, and the estimated work and
memory of a task; Settings shows the values. Messages say which limit applies.
