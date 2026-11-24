# Reports

The Reporting Center (`/reports`) and the download buttons on the experiment and performance analysis pages produce
reports **on request** from stored data. Nothing is cached or stored; generating a report writes an audit event
(`REPORT_GENERATED`, with the format and the experiments concerned). Every endpoint applies the same visibility as
the rest of the API: users see their own experiments, administrators all; others get 404.

| Report | Endpoint | Formats |
|---|---|---|
| One experiment | `GET /api/v1/experiments/{id}/report?format=` | PDF, CSV, JSON |
| A comparison | `GET /api/v1/analysis/report?ids=a,b,...&baseline=&basis=&format=` | PDF, CSV, JSON |
| The experiment history | `GET /api/v1/experiments/export?format=&status=&algorithm=&mode=&category=&search=&from=&to=&batch=&mine=` | CSV, JSON |

Downloads are GET requests, so a plain link downloads them with the session cookie. They answer
`Content-Disposition: attachment` with a file name such as `districompute-experiment-03fa983d-20261124-125407.pdf`,
and `Cache-Control: no-store`. An unknown format answers 400 `INVALID_FORMAT`.

## Contents

### Experiment report

- **PDF**: identification, owner, algorithm and version, mode and policy, status, runs, times, the recommendation and
  whether it was followed (with the override reason), input, dimensions, Core's work estimate (labelled as an
  estimate), component versions; parameters as given (the rest are Core's defaults); execution settings; a table of
  runs with digests; wall time per run as a chart; error messages of failed runs; every metric with count, median,
  minimum, maximum and standard deviation; each result's verification, digest and stored value.
- **CSV**: one row per run with its status, times, threads, partitions, workers, digest and verification, followed by
  one column per metric (`name [unit]`, and the source when a name has more than one).
- **JSON**: the experiment as `GET /api/v1/experiments/{id}` returns it, every metric value per run, the component
  versions, the time and the user.

### Comparison report

The same computation as `POST /api/v1/analysis/compare` ([analysis.md](analysis.md)).

- **PDF**: basis, baseline and why, the definitions of speedup and efficiency, findings, issues and caveats by
  severity, the experiment table, charts of the median time and of the speedup (with the baseline marked), and the
  time breakdown.
- **CSV**: one row per experiment: identification, mode, threads or workers, runs, baseline flag, basis, median,
  minimum, maximum and coefficient of variation on the basis, the wall, execution and waiting medians, speedup and
  its range, efficiency and its basis, result digest, one column per breakdown phase and per further measurement.
- **JSON**: the comparison as the API returns it, with versions, time and user.

### History export

One row (CSV) or object (JSON) per experiment matching the history page's filters, newest first, at most 5,000; the
JSON says whether the list was truncated.

## Formats

- **CSV** follows RFC 4180 (comma separator, CRLF, quotes doubled) in UTF-8 with a byte-order mark, so spreadsheets
  detect the encoding. Text that a spreadsheet would run as a formula (starting with `=`, `+`, `-`, `@`, a tab or a
  carriage return) is prefixed with an apostrophe; numbers are written unchanged. Times are ISO 8601 in UTC; durations
  are milliseconds.
- **PDF** is A4, generated with Apache PDFBox 3.0.8 and the standard Helvetica and Courier fonts. Characters these
  fonts cannot show (outside Windows-1252, for example Chinese or Arabic names) are replaced by `?` in the PDF; the CSV
  and JSON reports keep them. Pages carry the application version, the generation time and the user.
- **JSON** is UTF-8, with the same field names as the API.

## Limits

- A history export holds at most 5,000 experiments; a comparison at most 20.
- PDF reports summarise arrays of more than 12 values as "N values"; CSV and JSON keep them.
- Result values themselves are not embedded in reports; download them from the experiment page
  (`/api/v1/experiments/{id}/runs/{n}/result/download`).
