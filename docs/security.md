# Security

What the application protects, how, and what an operator must add. Configuration names are in
[configuration.md](configuration.md).

## 1. Users and sessions

- **Accounts** are created by administrators (Settings page or `POST /api/v1/users`). Passwords have 12 to 128
  characters and are stored as BCrypt hashes. The first administrator's password comes from
  `DISTRICOMPUTE_ADMIN_PASSWORD`, a file, or a generated owner-only file; it is never logged.
- **Roles:** `USER` and `ADMIN`. Administrators manage users, workers, settings and the fault lab, and see every
  experiment. Administrators cannot remove their own role or disable themselves.
- **Sign-in** is a form post to `/api/v1/auth/login` answering JSON; failures answer 401 without saying whether the
  user exists. After five failed sign-ins for one user name from one address within 15 minutes, further attempts from
  there are refused with 429 and `Retry-After` until the oldest failure is 15 minutes old (in memory; a restart
  forgets it).
- **Sessions** are server-side (cookie `JSESSIONID`, HttpOnly) and end after `security.session-timeout` of
  inactivity (8 hours). Behind HTTPS set `server.servlet.session.cookie.secure=true`.
- **CSRF:** every state-changing request must echo the `XSRF-TOKEN` cookie in the `X-XSRF-TOKEN` header (Spring
  Security's single-page-application setup); otherwise 403 `CSRF_TOKEN_INVALID`. Downloads are GET requests and
  change nothing but the audit log.
- **Unauthenticated API calls** answer 401 `UNAUTHENTICATED`, never a redirect. Public: the interface's static files,
  `GET /api/v1/system/info` (versions), `/actuator/health`, `/actuator/info`. The OpenAPI document and Swagger UI
  require a session.

## 2. Access to data

| Data | Who sees it |
|---|---|
| Experiments, their runs, results, events, reports | The owner and administrators; others get 404, so existence is not disclosed |
| Comparisons and workload groups | Built only from experiments the caller may see |
| Templates | The owner only |
| Batches | The owner and administrators |
| Uploaded inputs | The owner and administrators |
| Workers | Everyone signed in may read; only administrators register, approve, deactivate, activate or remove |
| Settings | Everyone sees the limits; administrators see the server's configuration (never secrets) and change settings |
| Fault lab | Administrators only, and only when `faults.enabled` is set |

## 3. Input

- Request bodies are validated (Bean Validation) and then by **DistriCompute-Core** against each algorithm's
  parameter schema, before anything runs; violations come back as RFC 9457 problems with field paths.
- **No code is executed from input.** Algorithms are Core's built-in implementations; polynomial input is parsed by
  a restricted parser into coefficients (`/api/v1/expressions/polynomial`), never evaluated as code.
- **Uploads** are CSV or plain text, UTF-8, at most `storage.max-upload-bytes`, parsed into numbers immediately
  (ragged rows and non-numbers rejected) and stored under generated names in the data directory.
- **Workload limits** (`limits.*`) reject tasks whose estimated work, memory, repetitions, threads or duration
  exceed the configured bounds; submissions per minute and active experiments per user are limited.
- Result files are served only through their experiment and run, by resolved paths inside the data directory.
- Reports: CSV cells that a spreadsheet would run as formulas are prefixed with an apostrophe; PDF reports contain
  text only.

## 4. Workers and their results

- Workers authenticate to the coordinator with the **cluster secret** (HMAC-SHA256 proof in both directions, done by
  DistriCompute-Distributed; the secret never crosses the network). The secret comes from `DISTRICOMPUTE_SECRET`, a
  file, or a generated owner-only file. Give it to workers through a secure channel; anyone who has it can join.
- **TLS** on the worker link is optional (`coordinator.tls-keystore`); without it, task data and results travel
  in clear text. Use TLS or a private network when workers are on other machines ([workers.md](workers.md)).
- **Approval:** with `workers.require-approval=true`, workers an administrator has not approved are deactivated
  when they connect and receive no work.
- **Results are not trusted as sent:** the coordinator checks each partial result's size and checksum and rejects
  invalid or duplicate ones; Core verifies the final result (for example Freivalds' check for matrix products); the
  application stores a result value only if its digest equals the verified result's digest, and shows an experiment
  as completed only after the runtime reported success.
- Fault-simulating workers are refused by the application's coordinator; only the isolated fault lab accepts them
  ([fault-lab.md](fault-lab.md)).

## 5. Secrets and logs

- Secrets (database password, administrator password, cluster secret, keystore password) come from the environment
  or from files; generated ones are written with owner-only permissions. They are not stored in the database, not
  shown on any page, and not logged.
- Configuration files with secrets (`.env`, `*.secret`, `*.password`) are ignored by Git.

## 6. Audit

`audit_events` records sign-ins and failed sign-ins (with the client address), password changes, user changes,
experiment creation and deletion, batch creation and cancellation, template changes, worker administration,
settings changes, report downloads and fault lab runs.

## 7. HTTP headers

Spring Security's defaults (`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, no caching of API responses)
and a Content Security Policy: `default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline';
object-src 'none'; frame-ancestors 'none'; base-uri 'self'`. Inline styles are allowed because the chart library
sets them.

## 8. What the operator must add

- **HTTPS:** the application serves HTTP. Put a reverse proxy with TLS in front of it when it is reachable from
  other machines, and set `server.servlet.session.cookie.secure=true` and `server.forward-headers-strategy=framework`.
- **Network:** expose the worker port only to worker machines; keep MySQL private.
- **Backups** of the database and the data directory, and of the secrets.

Not provided: password reset by e-mail, multi-factor authentication, single sign-on, encryption of result files at
rest.
