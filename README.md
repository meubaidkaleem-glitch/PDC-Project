# PDC-Project: DistriCompute

DistriCompute is a platform for running computational workloads (matrix operations, numerical methods, data
processing) **sequentially, on several threads, or across worker machines**, watching them live, and comparing the
measured performance of each strategy. It recommends an execution strategy for each task, verifies every result, and
produces reports.

This page explains how to install and use it. Detailed documentation is listed at the end.

![DistriCompute: overview page](https://raw.githubusercontent.com/meubaidkaleem-glitch/PDC-Project/feature/application-gui/docs/screenshots/02-overview.png)

## Quick install: Windows or Linux, nothing else needed

One download contains everything: a Java runtime, the application, its database and two workers. No Java, database,
Docker or Node.js has to be installed, and no administrator rights are needed.

| System | Download (newest release) |
|---|---|
| **Windows 10 / 11** (64-bit) | [DistriCompute-windows-x64.zip](https://github.com/meubaidkaleem-glitch/PDC-Project/releases/latest/download/DistriCompute-windows-x64.zip) |
| **Linux** (x86-64) | [DistriCompute-linux-x64.tar.gz](https://github.com/meubaidkaleem-glitch/PDC-Project/releases/latest/download/DistriCompute-linux-x64.tar.gz) |

Release notes, checksums and earlier versions: [Releases](https://github.com/meubaidkaleem-glitch/PDC-Project/releases).

**Windows**

1. Download the zip and unzip it (right-click, *Extract All*), for example to Documents.
2. Double-click **`DistriCompute.cmd`** in the unzipped folder. If SmartScreen says "Windows protected your PC",
   choose *More info*, then *Run anyway*.
3. The browser opens DistriCompute after a few seconds. Sign in with the user and password shown in the DistriCompute
   window (`First sign-in: user admin password ...`), then change the password in **Settings**.

Keep the window open while you use DistriCompute; close it to stop. Your data stays in
`%LOCALAPPDATA%\DistriCompute\data`, also when you unzip a newer version.

**Linux**

```bash
tar -xzf DistriCompute-linux-x64.tar.gz
```

```bash
DistriCompute-*-linux-x64/districompute.sh
```

The quick install runs everything on one computer and is reachable only from it. For several users or worker
machines, use the server installation in section 2. Details:
[standalone.md](https://github.com/meubaidkaleem-glitch/PDC-Project/blob/feature/application-gui/docs/standalone.md).
Then continue with [section 3, Usage](#3-usage).

## Repository layout

The project has three components, each on its own branch; they stay separate. The application includes the other
two as pinned Git submodules, so the downloads above and **the server installation are built from the
`feature/application-gui` branch**.

| Branch | Component | Contents |
|---|---|---|
| `main` | — | This guide |
| [`feature/core-engine`](https://github.com/meubaidkaleem-glitch/PDC-Project/tree/feature/core-engine) | DistriCompute-Core | Algorithms, validation, workload analysis, local parallel execution, result verification |
| [`feature/distributed-engine`](https://github.com/meubaidkaleem-glitch/PDC-Project/tree/feature/distributed-engine) | DistriCompute-Distributed | Coordinator, worker processes, scheduling, fault tolerance |
| [`feature/application-gui`](https://github.com/meubaidkaleem-glitch/PDC-Project/tree/feature/application-gui) | DistriCompute-Application | Web interface (React), backend (Spring Boot), MySQL; integrates Core and Distributed |

## 1. Requirements (server installation)

Only for the server installation built from the source code; the quick install above needs none of this.

| Software | Version | Needed for |
|---|---|---|
| Git | any recent | Cloning with submodules |
| JDK | 21 | Building and running the backend and the workers |
| Node.js | 22 | Building the web interface |
| Docker with Compose | recent | The Docker installation, the integration and end-to-end tests |
| MySQL | 8.4 | Only when installing without Docker |

The commands below are for a Unix shell (Linux, macOS, or WSL on Windows). The project was built and tested on Linux.
On Windows without WSL, use the quick install.

## 2. Server installation

### 2.1 Get the code and build it

```bash
git clone --recurse-submodules --branch feature/application-gui https://github.com/meubaidkaleem-glitch/PDC-Project.git districompute
```

```bash
cd districompute
```

```bash
scripts/build.sh
```

`scripts/build.sh` checks that Core and Distributed are at their pinned commits, builds them, builds and tests the web
interface, and builds the backend with the interface inside it. It also runs the backend's tests, which need Docker;
use `scripts/build.sh --skip-tests` to only build. It produces:

- `backend/target/districompute-backend-0.1.0-SNAPSHOT.jar` — the application (interface, API, coordinator);
- `components/distributed/distributed/target/districompute-distributed-0.1.0-SNAPSHOT-all.jar` — the worker.

### 2.2 Option A: run with Docker Compose (recommended)

Create your settings file from the example:

```bash
cp .env.example .env
```

Edit `.env`: set `DISTRICOMPUTE_DB_PASSWORD` (the database password) and, optionally, `DISTRICOMPUTE_ADMIN_PASSWORD`
(at least 12 characters) for the first administrator. Then start everything:

```bash
docker compose up -d --build
```

This starts MySQL 8.4, the application and two workers. Open <http://localhost:8080> and sign in as `admin` with the
password from `.env`. If you left it empty, a password was generated; read it with:

```bash
docker compose exec app cat /data/initial-admin-password
```

Useful commands:

| Command | Effect |
|---|---|
| `docker compose ps` | Status of the containers |
| `docker compose logs -f app` | Application log |
| `docker compose up -d --scale worker=4` | Run four workers instead of two |
| `docker compose down` | Stop everything; data is kept in Docker volumes |
| `docker compose down -v` | Stop and **delete** all data (database, results, generated secrets) |

### 2.3 Option B: run without Docker

1. Create the database (MySQL 8.4):

   ```sql
   CREATE DATABASE districompute CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
   CREATE USER 'districompute'@'%' IDENTIFIED BY 'choose-a-password';
   GRANT ALL PRIVILEGES ON districompute.* TO 'districompute'@'%';
   ```

2. Start the application. It creates the tables on first start and keeps results and generated secrets in the data
   directory:

   ```bash
   DISTRICOMPUTE_DB_URL=jdbc:mysql://localhost:3306/districompute DISTRICOMPUTE_DB_PASSWORD=choose-a-password DISTRICOMPUTE_ADMIN_PASSWORD=choose-an-admin-password java -jar backend/target/districompute-backend-0.1.0-SNAPSHOT.jar --districompute.data-dir=data
   ```

3. Start one or more workers, in other terminals or on other machines. They authenticate with the cluster secret the
   application generated in `data/cluster.secret` and connect to the worker port 7650:

   ```bash
   java -jar components/distributed/distributed/target/districompute-distributed-0.1.0-SNAPSHOT-all.jar worker --coordinator localhost:7650 --id worker-1 --max-concurrent-tasks 2 --secret-file data/cluster.secret
   ```

4. Open <http://localhost:8080> and sign in as `admin`.

### 2.4 Workers on other machines

Each worker machine needs Java 21, the worker jar and a copy of the cluster secret (`data/cluster.secret`, or
`docker compose exec app cat /data/cluster.secret`). Copy the secret through a secure channel: anyone who has it can
join the cluster. Make port 7650 of the application reachable from the workers (with Docker Compose, add
`"7650:7650"` to the `ports` of the `app` service and set `DISTRICOMPUTE_ADVERTISED_HOST` in `.env`), then start the
worker with `--coordinator <application-host>:7650`. On the **Workers** page, *Register a worker* shows the exact
command for a worker. Use TLS or a private network between machines
([workers guide](https://github.com/meubaidkaleem-glitch/PDC-Project/blob/feature/application-gui/docs/workers.md)).

### 2.5 Check the installation

| Command | What it checks |
|---|---|
| `scripts/compose-smoke.sh` | Builds the images, starts MySQL, the application and two workers with generated passwords, runs a distributed experiment, checks that its result is verified, then removes everything |
| `scripts/e2e.sh` | Drives the web interface in Chromium against the packaged application, MySQL and worker processes. Install the browser once first: `cd frontend && npx playwright install --with-deps chromium` |

### 2.6 Main settings

Settings are environment variables or `--name=value` arguments; the
[configuration reference](https://github.com/meubaidkaleem-glitch/PDC-Project/blob/feature/application-gui/docs/configuration.md)
lists all of them.

| Setting | Default | Meaning |
|---|---|---|
| `DISTRICOMPUTE_DB_URL`, `DISTRICOMPUTE_DB_USER`, `DISTRICOMPUTE_DB_PASSWORD` | `jdbc:mysql://localhost:3306/districompute`, `districompute`, — | Database |
| `DISTRICOMPUTE_HTTP_PORT` | `8080` | Port of the web interface |
| `DISTRICOMPUTE_ADMIN_PASSWORD` | generated | First administrator's password (first start only) |
| `DISTRICOMPUTE_SECRET` | generated | Cluster secret shared with the workers |
| `--districompute.data-dir` | `./data` | Results, uploads, generated secrets |
| `--districompute.coordinator.port` | `7650` | Port the workers connect to |
| `--districompute.limits.max-active-experiments-per-user` | `5` | Queued or running experiments per user |
| `--districompute.faults.enabled` | `false` | Enables the fault lab (test and demonstration only) |

## 3. Usage

### 3.1 First steps

1. **Sign in** as `admin` (quick install: with the password shown in the DistriCompute window), open **Settings** and
   change the password.
2. Under **Settings → Users**, create accounts for other people (role *user* or *administrator*). Users see their own
   experiments; administrators see everything and manage workers, settings and the fault lab.
3. Choose a level at the top of the page: **Beginner** shows the essential fields and explanations, **Advanced**
   shows every setting. The sun/moon button switches between light and dark themes.

### 3.2 Run a task

1. Open **New task** and choose a category (matrix computations, numerical methods, data processing), then an
   algorithm, for example *Matrix multiplication*.
2. Enter the input:
   - **Generate test data**: give the size (for example 512 × 512); a seed makes it reproducible.
   - **Use my data**: paste numbers or upload a CSV/text file; matrix sizes are read from the file. Polynomials are
     typed as expressions such as `3x^2 - 2x + 1`.

   Mistakes are shown next to the field before anything runs.
3. Click **Analyse and recommend**. The advisor shows, for each execution mode, a verdict, an estimated time and
   where the estimate comes from (*measured*, *calibrated estimate*, *theoretical estimate* or *no estimate*), the
   reasons, and the resources it considered. If it has nothing to base an estimate on, it says so.
4. Choose the **execution mode**: *Sequential CPU*, *Parallel CPU (threads)* or *Distributed CPU (workers)*. If you do
   not follow the recommendation, you are asked why. In advanced mode you can also set threads, partitions, the
   scheduling policy, timeouts and **repetitions** (several runs for reliable medians).
5. Name the experiment and click **Run**. The experiment page opens.

To reuse a task, click **Save as template**; next time choose it under **Start from a template**.

![New task with the advisor's recommendation](https://raw.githubusercontent.com/meubaidkaleem-glitch/PDC-Project/feature/application-gui/docs/screenshots/03-new-task-and-advisor.png)

### 3.3 Watch it and read the results

The experiment page has five tabs:

| Tab | Shows |
|---|---|
| Live monitor | Partitions completed, running and failed, attempts and retries, active workers, and a timeline of which worker ran which partition |
| Results | Whether the result was verified (and how), its digest, a preview and a download (CSV or JSON) |
| Metrics | Wall time per run and statistics (median, minimum, maximum, standard deviation) of every measurement |
| Events | Everything the runtime and the application reported, in order |
| Configuration | Mode, settings, recommendation, parameters and workload |

Buttons: **Cancel** a running experiment, **Run again**, **Copy and edit**, **Report**, delete.

![Live monitor of a distributed run](https://raw.githubusercontent.com/meubaidkaleem-glitch/PDC-Project/feature/application-gui/docs/screenshots/04-experiment-live-monitor.png)

### 3.4 Compare strategies

- **Experiments** lists all your experiments with filters and sorting. Select two or more and click **Compare**.
- **Performance analysis** compares experiments of the same workload: median time, range, variation, **speedup**
  over a baseline (a sequential experiment by default), **efficiency** per thread or worker, where the time goes, and
  findings in plain language. Warnings appear when experiments are not comparable (different sizes, single runs,
  high variation, runs that competed with each other).
- **Batches** is the easiest way to benchmark: choose a completed experiment or template, tick the modes and thread
  counts (for example sequential, 2 and 4 threads, distributed), set the repetitions and submit. The experiments run
  one after another so they do not slow each other down; then click **Compare** on the batch page.

![Performance analysis of a benchmark batch](https://raw.githubusercontent.com/meubaidkaleem-glitch/PDC-Project/feature/application-gui/docs/screenshots/07-performance-analysis.png)

More screenshots of every page: [docs/screenshots](https://github.com/meubaidkaleem-glitch/PDC-Project/blob/feature/application-gui/docs/screenshots/README.md).

### 3.5 Reports

**Reports** downloads an experiment report, a comparison report or your experiment history as **PDF** (to read or
print), **CSV** (for spreadsheets) or **JSON** (for programs). The experiment and analysis pages have download
buttons too.

### 3.6 Workers and the cluster

**Workers** lists the connected workers with what they reported (processors, memory, algorithms, slots), their
status, load and history. Administrators can register, approve, deactivate, activate and remove workers and test a
connection. **Cluster topology** draws the coordinator and its workers. If a worker stops during a run, its work is
retried on the other workers automatically.

### 3.7 For administrators

- **Settings**: the default scheduling policy of the cluster, the limits in force, users.
- **Fault lab** (when started with `--districompute.faults.enabled=true`): inject a fault into a simulated worker on
  an isolated test cluster (disconnection, missed heartbeats, corrupted or duplicate results, failures, hangs,
  slowness) and see how it is detected and recovered from. It never affects real experiments or workers.

### 3.8 Using the API

Everything in the interface is available through the REST API under `/api/v1`. When signed in, the API description
is at <http://localhost:8080/swagger-ui/index.html>. Requests use the session cookie, and every request that changes
something must send the `XSRF-TOKEN` cookie back in an `X-XSRF-TOKEN` header. With `curl`:

```bash
curl -s -c cookies -b cookies http://localhost:8080/api/v1/auth/session > /dev/null
```

```bash
curl -s -c cookies -b cookies -H "X-XSRF-TOKEN: $(awk '/XSRF-TOKEN/ {print $7}' cookies)" --data-urlencode username=admin --data-urlencode 'password=your-password' http://localhost:8080/api/v1/auth/login
```

```bash
curl -s -c cookies -b cookies http://localhost:8080/api/v1/auth/session > /dev/null
```

```bash
curl -s -b cookies -H "X-XSRF-TOKEN: $(awk '/XSRF-TOKEN/ {print $7}' cookies)" -H 'Content-Type: application/json' -d '{"name":"API example","task":{"algorithm":"matrix.multiply","parameters":{"rowsA":200,"colsA":200,"colsB":200},"inputs":{},"execution":{"mode":"DISTRIBUTED_CPU"}}}' http://localhost:8080/api/v1/experiments
```

The answer contains the experiment's id; `GET /api/v1/experiments/{id}` returns its status, runs and results.

## 4. Troubleshooting

| Problem | What to do |
|---|---|
| Quick install: the window closes or shows an error | Read the messages in the window; if port 8080 is taken, set `DISTRICOMPUTE_HTTP_PORT` (see [standalone.md](https://github.com/meubaidkaleem-glitch/PDC-Project/blob/feature/application-gui/docs/standalone.md#6-troubleshooting)) |
| `docker compose up` stops with *set DISTRICOMPUTE_DB_PASSWORD in .env* | Create `.env` from `.env.example` and set the password |
| Port 8080 is already in use | Stop the other program, or publish another port: in `docker-compose.yml`, change the `app` service's `ports` entry from `...:8080:8080` to `...:9090:8080` (without Docker, set `DISTRICOMPUTE_HTTP_PORT=9090`) |
| A worker does not appear on the Workers page | Check that it uses the same cluster secret as the application, that port 7650 is reachable, and read the worker's output (`docker compose logs worker`) |
| Sign-in answers *Too many failed sign-ins* | Five wrong passwords in 15 minutes block further attempts from that address for a while; wait and retry |
| An administrator forgot their password | Another administrator can set a new one: `PATCH /api/v1/users/{id}` with `{"password": "..."}` |
| A task is refused because of a limit | The message names the limit; administrators see all limits under Settings and can change them in the configuration |
| *Distributed CPU* is not recommended or a run waits | No worker is connected or all are busy; start workers (section 2.4) |
| The build fails in the tests | The backend tests need Docker; start it, or build with `scripts/build.sh --skip-tests` |

## 5. Documentation

All documents are on the
[`feature/application-gui`](https://github.com/meubaidkaleem-glitch/PDC-Project/tree/feature/application-gui/docs)
branch:

| Document | Contents |
|---|---|
| [Quick install packages](https://github.com/meubaidkaleem-glitch/PDC-Project/blob/feature/application-gui/docs/standalone.md) | The Windows and Linux packages: data folder, settings, troubleshooting, how they are built and tested |
| [User guide](https://github.com/meubaidkaleem-glitch/PDC-Project/blob/feature/application-gui/docs/user-guide.md) | The interface in detail |
| [Screenshots](https://github.com/meubaidkaleem-glitch/PDC-Project/blob/feature/application-gui/docs/screenshots/README.md) | Every page of the running application |
| [Deployment](https://github.com/meubaidkaleem-glitch/PDC-Project/blob/feature/application-gui/docs/deployment.md) | Building, Docker Compose, running without Docker, backups and upgrades |
| [Configuration](https://github.com/meubaidkaleem-glitch/PDC-Project/blob/feature/application-gui/docs/configuration.md) | Every setting |
| [Security](https://github.com/meubaidkaleem-glitch/PDC-Project/blob/feature/application-gui/docs/security.md) | Accounts, access, secrets, and what to add in production (HTTPS) |
| [Workers](https://github.com/meubaidkaleem-glitch/PDC-Project/blob/feature/application-gui/docs/workers.md) | Worker options, statuses, administration, TLS |
| [Performance analysis](https://github.com/meubaidkaleem-glitch/PDC-Project/blob/feature/application-gui/docs/analysis.md) | How comparisons, speedup and efficiency are computed |
| [API](https://github.com/meubaidkaleem-glitch/PDC-Project/blob/feature/application-gui/docs/api.md) | Endpoints and conventions |
| [Testing](https://github.com/meubaidkaleem-glitch/PDC-Project/blob/feature/application-gui/docs/testing.md) | Test suites and how to run them |
| [Implementation report](https://github.com/meubaidkaleem-glitch/PDC-Project/blob/feature/application-gui/docs/implementation-report.md) | What was built and tested, limitations |

The Core and Distributed components have their own documentation in their branches.
