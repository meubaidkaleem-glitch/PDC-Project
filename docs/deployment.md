# Deployment

DistriCompute runs as one Java process (the backend: interface, API and the embedded Distributed coordinator), a
MySQL 8.4 database, and any number of worker processes. [configuration.md](configuration.md) lists every setting,
[security.md](security.md) what to secure, [workers.md](workers.md) how to run workers.

For one person on one computer there is a simpler way: the Windows and Linux packages bring their own Java, an
embedded database and two workers, and need no installation ([standalone.md](standalone.md)).

## 1. Build

Requirements: Git, JDK 21, Node.js 22, and Docker for the integration tests and the images.

```bash
git clone --branch feature/application-gui https://github.com/meubaidkaleem-glitch/PDC-Project.git districompute
cd districompute
scripts/build.sh
```

`scripts/build.sh` checks out the pinned Core and Distributed submodules ([integration.md](integration.md)),
builds them, type-checks, tests and builds the interface, and builds the backend with the interface inside
(`./mvnw verify -pl backend -Pwith-frontend`, which runs the backend tests). Results:

- `backend/target/districompute-backend-0.1.0-SNAPSHOT.jar` — the application;
- `components/distributed/distributed/target/districompute-distributed-0.1.0-SNAPSHOT-all.jar` — the worker.

`scripts/build.sh --skip-tests` skips the tests.

## 2. Docker Compose

```bash
scripts/build.sh
cp .env.example .env        # set DISTRICOMPUTE_DB_PASSWORD (and optionally DISTRICOMPUTE_ADMIN_PASSWORD)
docker compose up -d --build
```

This starts MySQL 8.4 (volume `mysql-data`), the application on <http://localhost:8080> (volume `app-data` for
results and generated secrets) and two workers. The images package the jars from the build, run as an unprivileged
user, and the base images are pinned by digest.

- **Sign in** as `admin` with `DISTRICOMPUTE_ADMIN_PASSWORD`, or read the generated password:
  `docker compose exec app cat /data/initial-admin-password`.
- **More workers:** `docker compose up -d --scale worker=4`. Workers read the cluster secret from the application's
  volume (read-only) and reconnect by themselves when the application restarts.
- **Workers on other machines:** publish port 7650 of the `app` service, set `DISTRICOMPUTE_ADVERTISED_HOST` to the
  name they reach it by, consider TLS ([workers.md](workers.md)), and give them the secret from
  `docker compose exec app cat /data/cluster.secret` through a secure channel.
- **Reachable from other machines:** `DISTRICOMPUTE_HTTP_BIND=0.0.0.0` in `.env`; put HTTPS in front
  ([security.md](security.md)).

`scripts/compose-smoke.sh` checks a deployment end to end: it builds the images, starts everything with generated
passwords, waits until the application is healthy and both workers have registered, runs a distributed experiment,
checks that its result is verified, and removes everything. CI runs it on every push.

## 3. Without Docker

1. Create the database and user:

   ```sql
   CREATE DATABASE districompute CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
   CREATE USER 'districompute'@'%' IDENTIFIED BY '<password>';
   GRANT ALL PRIVILEGES ON districompute.* TO 'districompute'@'%';
   ```

2. Start the application (Flyway creates the schema):

   ```bash
   DISTRICOMPUTE_DB_URL=jdbc:mysql://db-host:3306/districompute DISTRICOMPUTE_DB_PASSWORD=<password> java -jar districompute-backend-0.1.0-SNAPSHOT.jar --districompute.data-dir=/var/lib/districompute
   ```

3. Start workers ([workers.md](workers.md)):

   ```bash
   java -jar districompute-distributed-0.1.0-SNAPSHOT-all.jar worker --coordinator app-host:7650 --secret-file cluster.secret
   ```

Health: `GET /actuator/health` (and `/actuator/health/liveness`, `/actuator/health/readiness`) answer without a
session. Shutdown is graceful (`SIGTERM`).

## 4. Operations

- **Backups:** the MySQL database and the data directory together (result files are referenced from the database).
- **Upgrades:** stop the application, back up, start the new version; Flyway applies new migrations
  (`database/migrations`). Experiments that were queued or running when the application stopped are marked failed
  with the reason (their runs cannot be resumed, [fault-tolerance.md](fault-tolerance.md)); batches do not continue.
- **Logs:** standard output. Audit events (sign-ins, submissions, worker and settings changes, reports, lab runs)
  are in the `audit_events` table.
- **Resources:** local runs use the application's processors; the advisor and the limits keep workloads within
  `limits.*`. Give the JVM enough memory for the largest workload you allow (the images use 75 % of the container's
  memory).
