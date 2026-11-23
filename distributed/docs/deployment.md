# Deployment

How to run a coordinator and workers on several machines of a laboratory LAN. For one machine, the local
demonstration ([§9](#9-local-demonstration)) starts everything with one command.

## 1. What runs where

| Process | Machines | Listens on | Needs |
|---|---|---|---|
| Coordinator | One machine (or inside the Application's JVM, [integration-contract.md](integration-contract.md)) | TCP 7650 for workers; optionally TCP 8650 for the HTTP API | JDK 21+, the shared secret |
| Worker | Every compute machine, one process per machine (it runs several attempts at once) | Nothing: workers only connect out | JDK 21+, the shared secret, the coordinator's address |
| Application | Contributor 3's server | — | The coordinator's Java API, or its HTTP API and token |

All processes run the same runnable jar, `districompute-distributed-0.1.0-SNAPSHOT-all.jar`, which contains
DistriCompute-Core. Use **the same build on every machine**. The coordinator refuses workers with another
protocol version, and schedules an algorithm only on workers that offer a compatible version of it.

## 2. Build and distribute

```bash
./mvnw install -DskipTests
```

```bash
./mvnw -f distributed/pom.xml package
```

Copy `distributed/target/districompute-distributed-0.1.0-SNAPSHOT-all.jar` and the `scripts/` directory to each
machine, for example to `/opt/districompute/`.

## 3. The shared secret

Every process proves knowledge of one cluster secret (at least 16 bytes) at registration. The secret never
crosses the network ([network-protocol.md §4](network-protocol.md#4-authentication)).

```bash
sudo install -d -m 700 /etc/districompute
```

```bash
head -c 32 /dev/urandom | base64 | sudo tee /etc/districompute/cluster.secret > /dev/null
```

```bash
sudo chmod 600 /etc/districompute/cluster.secret
```

Copy the file to every machine over a secure channel (for example `scp`), owned by the account that runs the
process and readable only by it. On Windows, restrict the file to that account
(`icacls cluster.secret /inheritance:r /grant:r "%USERNAME%:R"`). Instead of a file, the secret can come from the
`DISTRICOMPUTE_SECRET` environment variable. There is deliberately no command-line option for it, because other
users can read command lines.

**Clocks.** A registration proof is valid for ±5 minutes around the coordinator's clock. Keep the machines'
clocks synchronised (NTP, or Windows Time). Otherwise workers are refused with `AUTHENTICATION_FAILED`.

**Changing the secret.** Write the new secret on every machine, then restart the coordinator and the workers.
Connected workers keep their registration until they reconnect.

## 4. Configuration files

Every command-line option can be set in a properties file, with the option name without the dashes as the key.
Options on the command line override the file.

* [examples/config/coordinator.properties](../examples/config/coordinator.properties): port, secret file,
  default policy, failure-detection timing, TLS, HTTP API.
* [examples/config/worker.properties](../examples/config/worker.properties): coordinator address, id, slots,
  memory per attempt, labels, algorithms, TLS, drain timeout.

The complete list of options is printed by `java -jar districompute-distributed-0.1.0-SNAPSHOT-all.jar help`, and
the worker options are explained in [worker-lifecycle.md §1](worker-lifecycle.md#1-starting-a-worker).

**Sizing a worker.**

* `max-concurrent-tasks`: the cores you want the worker to use. Each attempt runs Core's single-threaded kernel
  for one partition.
* Heap (`-Xmx` in `JAVA_OPTS`): by default an attempt may use 70 % of the heap divided by the slots. The
  coordinator does not send a worker partitions that Core estimates to be larger. For data-heavy algorithms
  (matrix multiplication, sorting), give 1-2 GiB per slot.
* `id`: a stable name per machine (for example its host name). The coordinator keeps counters and performance
  history per id, and refuses a second process with the same id.

## 5. Starting

On the coordinator machine:

```bash
scripts/start-coordinator.sh /etc/districompute/coordinator.properties
```

On each worker machine:

```bash
JAVA_OPTS=-Xmx4g scripts/start-worker.sh /etc/districompute/worker.properties
```

On Windows, `scripts\start-coordinator.cmd` and `scripts\start-worker.cmd` do the same. These two `.cmd` files
were not run on Windows by the author (no Windows machine was available). They only call `java -jar` with a
configuration file.

Workers can start before the coordinator. They retry with exponential backoff (0.5 s up to 10 s), and they
reconnect by themselves after a coordinator restart or a network failure.

**Stopping.** `Ctrl+C` or `SIGTERM` stops a worker gracefully: it accepts no new work, finishes running attempts
(up to `drain-timeout-ms`), and disconnects. Stopping the coordinator cancels running tasks (they end
`CANCELLED`), and the workers keep trying to reconnect.

**As a service (Linux).** An example systemd unit. It was not tested in this repository's environment, which has
no systemd.

```ini
[Unit]
Description=DistriCompute worker
After=network-online.target
Wants=network-online.target

[Service]
User=districompute
Environment=JAVA_OPTS=-Xmx4g
ExecStart=/opt/districompute/scripts/start-worker.sh /etc/districompute/worker.properties
# SIGTERM lets running attempts finish; allow a little more than drain-timeout-ms.
TimeoutStopSec=45
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

## 6. Firewall

Only the coordinator accepts connections.

| Port | Direction | Allow from |
|---|---|---|
| 7650/tcp (worker port) | Into the coordinator | The worker machines' subnet only |
| 8650/tcp (HTTP API) | Into the coordinator | Nobody, if it is bound to `127.0.0.1` (the default). Otherwise only the Application's server |

Examples, for a laboratory subnet `192.168.1.0/24`:

```bash
sudo ufw allow from 192.168.1.0/24 to any port 7650 proto tcp
```

```bash
sudo firewall-cmd --permanent --add-rich-rule='rule family="ipv4" source address="192.168.1.0/24" port port="7650" protocol="tcp" accept' && sudo firewall-cmd --reload
```

On Windows (PowerShell as administrator):

```powershell
New-NetFirewallRule -DisplayName "DistriCompute coordinator" -Direction Inbound -Protocol TCP -LocalPort 7650 -RemoteAddress 192.168.1.0/24 -Action Allow
```

## 7. TLS

The shared secret authenticates both sides at registration, but messages after registration are not signed.
On a network where someone could modify traffic, enable TLS on the worker port. TLS 1.3 (or 1.2) then protects
every message, and workers verify the coordinator's certificate and host name.

Create a key pair for the coordinator. The certificate must name the host or address that workers connect to:

```bash
keytool -genkeypair -alias coordinator -keyalg EC -groupname secp256r1 -dname "CN=coordinator.lab.local" -ext "SAN=dns:coordinator.lab.local,ip:192.168.1.10" -validity 365 -storetype PKCS12 -keystore coordinator.p12
```

Export the certificate, and build the trust store that the workers use:

```bash
keytool -exportcert -rfc -alias coordinator -keystore coordinator.p12 -file coordinator.crt
```

```bash
keytool -importcert -noprompt -alias coordinator -file coordinator.crt -storetype PKCS12 -keystore truststore.p12
```

Then set `tls-keystore` and `tls-keystore-password` in the coordinator's configuration, and `tls-truststore` and
`tls-truststore-password` in each worker's. Put the passwords in the configuration files (mode 600), not on the
command line. A certificate signed by your organisation's certificate authority works the same way: import the
authority's certificate into the trust store instead.

`--tls-no-hostname-verification` accepts a trusted certificate that names another host. Use it only in a
laboratory, because it allows any holder of a trusted certificate to impersonate the coordinator.

The same procedure, with a short-lived certificate, is exercised by `TlsRegistrationTest`.

## 8. The HTTP API

The HTTP API (`http-port`) serves the Application and the `submit`, `status` and `advise` clients
([integration-contract.md §2.5](integration-contract.md#25-http-api-of-a-standalone-coordinator--implemented)).
It is plain HTTP with a bearer token:

* Create a token like the secret (`head -c 24 /dev/urandom | base64`), store it in a file with mode 600, and set
  `http-token-file`. Clients send `Authorization: Bearer <token>`, or use `--token-file`.
* Keep `http-bind=127.0.0.1` when the Application runs on the coordinator machine. Otherwise bind it to an
  internal interface and put a TLS reverse proxy (nginx, Caddy) in front of it, so the token is never sent in clear
  text.
* Allow the browser origin of a front end with `http-cors-origin` only if the front end calls the API directly.

## 9. Local demonstration

On one machine, after building the jar:

```bash
distributed/scripts/local-demo.sh 3
```

It creates a temporary secret and token and starts a coordinator and three worker processes on loopback. Then it
asks for advice and runs the example tasks in [examples/tasks](../examples/tasks) (prime counting, matrix
multiplication and sorting on the workers, and prime counting sequentially on the coordinator). It prints each
result and the cluster status, and stops the workers gracefully. `SLOTS=2` gives each worker two slots. Other
task files can be passed after the worker count.

## 10. Checking a deployment

```bash
java -jar districompute-distributed-0.1.0-SNAPSHOT-all.jar status --api http://127.0.0.1:8650 --token-file /etc/districompute/api.token
```

lists every worker with its status and slots. Then run a small task with `submit --wait` (see
[examples/tasks](../examples/tasks)).

| Symptom | Likely cause |
|---|---|
| Worker log: `Invalid registration proof (wrong shared secret?)` | The secret files differ, or the clocks differ by more than 5 minutes |
| Worker log: `The coordinator could not prove the shared secret` | The worker reached another coordinator, or that coordinator uses another secret |
| `Worker id … is already registered` | Two processes use the same `id` |
| `Protocol version … is not supported (this runtime speaks …)` | Different builds of the runtime on the coordinator and the worker |
| Worker `SUSPECT` or disconnected now and then | Heartbeats delayed by long garbage-collection pauses or a congested network. Give the worker more heap, or raise `suspect-after-ms` and `heartbeat-timeout-ms` |
| Task fails with `No connected worker can run … partitions`, listing each worker's reason | No worker offers the algorithm in a compatible version, or every worker's memory limit is below Core's estimate for a partition |
| Worker log: `Cannot reach coordinator …` with a TLS (`SSLHandshakeException`) message | The trust store does not contain the coordinator's certificate, or the certificate does not name the host the worker connects to. Without TLS: wrong address, coordinator not running, or a firewall |
