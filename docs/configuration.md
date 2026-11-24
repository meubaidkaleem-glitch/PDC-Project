# Configuration

The backend is a Spring Boot application. Every setting below can be given as a command-line argument
(`--districompute.data-dir=/data`), in an `application.yml` next to the jar, or as an environment variable using
Spring Boot's relaxed binding: upper case, dots become underscores, dashes are removed
(`districompute.limits.max-active-experiments-per-user` → `DISTRICOMPUTE_LIMITS_MAXACTIVEEXPERIMENTSPERUSER`).
Administrators see the effective values on the Settings page (no secrets).

## 1. Database, web server, secrets

| Setting | Default | Meaning |
|---|---|---|
| `DISTRICOMPUTE_DB_URL` | `jdbc:mysql://localhost:3306/districompute` | MySQL 8.4 database. Flyway creates and migrates the schema at startup |
| `DISTRICOMPUTE_DB_USER` | `districompute` | Database user |
| `DISTRICOMPUTE_DB_PASSWORD` | (empty) | Database password. Use the environment or a secret store, never a committed file |
| `DISTRICOMPUTE_HTTP_PORT` | `8080` | HTTP port of the interface and the API |
| `DISTRICOMPUTE_ADMIN_PASSWORD` | — | Password of the first administrator, used only when the database has no user. At least 12 characters |
| `districompute.security.admin-password-file` | — | The same, read from a file |
| `DISTRICOMPUTE_SECRET` | — | Cluster secret shared with the workers |
| `districompute.coordinator.secret-file` | — | The same, read from a file |

Without an administrator password the application writes a random one to `<data-dir>/initial-admin-password`
(owner-only permissions); without a cluster secret it writes one to `<data-dir>/cluster.secret`. Neither is logged.

## 2. `districompute.*`

| Property | Default | Meaning |
|---|---|---|
| `data-dir` | `./data` | Result files, uploads, generated secrets. Back it up with the database |
| `coordinator.bind-address` | `0.0.0.0` | Address of the worker port |
| `coordinator.port` | `7650` | Worker port of the embedded coordinator (`0`: chosen by the system) |
| `coordinator.advertised-host` | `localhost` | Host name workers are told to use in their start instructions |
| `coordinator.tls-keystore` | — | PKCS#12 keystore: enables TLS on the worker link ([workers.md](workers.md)) |
| `coordinator.tls-keystore-password-file` | — | File with the keystore password |
| `coordinator.heartbeat-interval` | `2s` | How often workers send heartbeats |
| `coordinator.suspect-after` | `5s` | Without heartbeat for this long a worker is suspect and gets no new work |
| `coordinator.heartbeat-timeout` | `10s` | Without heartbeat for this long a worker is lost; its attempts are retried |
| `coordinator.max-workers` | `256` | Most connected workers |
| `limits.max-active-experiments-per-user` | `5` | Queued or running experiments per user; a batch counts once |
| `limits.submissions-per-minute` | `30` | Submissions per user and minute; a batch counts once |
| `limits.max-repetitions` | `20` | Repetitions per experiment |
| `limits.max-batch-size` | `50` | Experiments per batch |
| `limits.max-estimated-operations` | `1e13` | Largest workload, by Core's estimate |
| `limits.max-estimated-memory-bytes` | `2147483648` | Largest estimated peak memory |
| `limits.max-threads` | `0` | Most threads per parallel run (`0`: the server's processors) |
| `limits.max-run-duration` | `1h` | Deadline of a run unless the task sets a shorter one |
| `storage.max-result-bytes` | `536870912` | Largest result value stored as a file; larger values keep Core's summary only |
| `storage.max-upload-bytes` | `67108864` | Largest uploaded input file |
| `storage.preview-rows`, `storage.preview-columns` | `20`, `12` | Default size of result previews |
| `security.admin-username` | `admin` | Name of the first administrator |
| `security.session-timeout` | `8h` | Idle time after which a session ends |
| `workers.require-approval` | `false` | Workers not approved by an administrator are deactivated when they connect |
| `faults.enabled` | `false` | Enables the fault lab ([fault-lab.md](fault-lab.md)); for test and demonstration environments |

## 3. Settings changed at run time

The Settings page (administrators) changes the coordinator's **default scheduling policy**. The value is stored in
`app_settings`, applied at once and again at every start, and audited. Everything else requires a restart.

## 4. Spring Boot settings worth knowing

| Property | Use |
|---|---|
| `server.servlet.session.cookie.secure=true` | Behind HTTPS, so the session cookie is only sent over TLS |
| `server.forward-headers-strategy=framework` | Behind a reverse proxy that sets `X-Forwarded-*` |
| `logging.level.org.districompute=DEBUG` | More detail from the application |
| `springdoc.api-docs.enabled=false`, `springdoc.swagger-ui.enabled=false` | Hide the OpenAPI document and Swagger UI (they require a session anyway) |
