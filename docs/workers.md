# Workers

Workers are separate processes of DistriCompute-Distributed that connect to the coordinator embedded in the
application and execute partitions of distributed runs. Any machine with Java 21 can be a worker. The worker itself
is documented in the Distributed component (`components/distributed/distributed/docs/`); this page covers running
workers for the application and managing them on the Workers page.

## 1. Starting a worker

```bash
java -jar districompute-distributed-0.1.0-SNAPSHOT-all.jar worker --coordinator app-host:7650 --id worker-1 --secret-file cluster.secret --max-concurrent-tasks 4
```

| Option | Meaning |
|---|---|
| `--coordinator host:port` | The application's worker port (`districompute.coordinator.port`, 7650) |
| `--secret-file file` | The cluster secret (or the `DISTRICOMPUTE_SECRET` environment variable) |
| `--id name` | Worker id (default: host name and process id) |
| `--max-concurrent-tasks n` | Slots: partitions run at the same time (default: processors) |
| `--max-task-memory-mb n` | Refuse partitions estimated above this memory |
| `--labels k=v,...` | Labels shown on the Workers page |
| `--algorithms a,b` | Only accept these algorithms |
| `--tls-truststore file`, `--tls-truststore-password p` | Trust the coordinator's TLS certificate |

The **cluster secret** is the one the application uses: `DISTRICOMPUTE_SECRET`, the configured
`coordinator.secret-file`, or the generated `<data-dir>/cluster.secret`. Copy it to the worker through a secure
channel and keep it readable by the worker's user only.

On the Workers page, **Register a worker** (administrators) records an expected worker id, approves it and shows the
exact start command and a properties file for it. The command never contains the secret.

With Docker Compose, workers are a service: `docker compose up -d --scale worker=4` ([deployment.md](deployment.md)).

## 2. What the application shows

For each worker: the capabilities it reported at registration (processors, memory, operating system, Java and
component versions, algorithms, slots, labels, GPU: none), its status and the reason, load (running partitions, CPU,
free memory), last heartbeat, partitions completed and failed, counters (assigned, succeeded, failed, rejected,
timed out, lost, invalid and duplicate results), and the measured throughput per algorithm. Nothing is filled in by
the application.

| Status | Meaning |
|---|---|
| `ACTIVE` | Connected and receiving work |
| `SUSPECT` | Heartbeats are late (`coordinator.suspect-after`); no new work |
| `DISCONNECTED` | Connection closed or heartbeat timeout; its running attempts are retried elsewhere |
| `DRAINING` | Announced a graceful shutdown; running partitions may finish, no new ones |
| `QUARANTINED` | Excluded by the runtime after repeated invalid results; connected, no work |
| `DEACTIVATED` | Excluded by an administrator (or not approved) |

The cluster topology page draws the coordinator and its workers with their current load.

## 3. Administration

| Action | Effect |
|---|---|
| Approve | Allows a worker when `workers.require-approval=true` |
| Deactivate (with a reason) | No new work; running partitions finish; the reason is shown and audited |
| Activate | Receives work again |
| Remove (with a reason) | A connected worker is told to stop and is forgotten; its running work is retried elsewhere |
| Test connection | Checks that the worker port is open and when the worker's last heartbeat arrived |

## 4. TLS

Set `districompute.coordinator.tls-keystore` (PKCS#12) and `coordinator.tls-keystore-password-file` on the
application; give workers a truststore with the certificate (`--tls-truststore`). The start instructions on the
Workers page include the TLS options when TLS is enabled. Without TLS, task data and results travel unencrypted;
use a private network.

## 5. Failures

When a worker process dies or its connection breaks, the coordinator marks its attempts lost and retries the
partitions on other workers, within each task's attempt budget. The end-to-end tests kill a worker process during a
run and check that the run completes with a verified result ([testing.md](testing.md)); the fault lab shows the
other failure kinds ([fault-lab.md](fault-lab.md), [fault-tolerance.md](fault-tolerance.md)).
