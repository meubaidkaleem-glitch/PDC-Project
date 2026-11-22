# DistriCompute Worker Protocol (DCP) — version 0.1

| | |
|---|---|
| Protocol version | `0.1` (**draft**: may change until the team agrees on `1.0`) |
| Transport | TCP, optionally TLS; workers connect to the coordinator |
| Encoding | UTF-8 JSON, one object per line (newline-delimited JSON) |
| Implemented in | `org.districompute.distributed.protocol`, `.communication`, `.security` |
| Status | Messages, framing, validation and authentication: implemented and tested (Phase 2). Connection and attempt behaviour (§6–7): specified here, implemented in Phases 3–7. |

The Application does not speak this protocol. It uses the coordinator's Java or HTTP API
([integration-contract.md](integration-contract.md)).

## 1. Transport and framing

* A worker opens one TCP connection to the coordinator (default port **7650**) and keeps it for its whole
  registration. All messages in both directions use this connection.
* Each message is one **frame**: the UTF-8 bytes of a single JSON object, followed by a line feed (`\n`). A
  carriage return before the line feed is removed. Empty lines are ignored. JSON never contains a raw line
  feed (inside strings it is escaped as `\n`), so the line feed is an unambiguous delimiter.
* **Size limit.** Each side has `maxMessageBytes` (default 64 MiB at the coordinator, 64 MiB at workers). A
  frame that passes the limit before its line feed is rejected without buffering the rest. The receiver sends
  `ERROR` `MESSAGE_TOO_LARGE` and closes the connection. The coordinator announces its limit in `REGISTER_ACK`,
  and each worker announces its own in its capabilities. Senders never send frames larger than the peer's
  limit.
* **Partial reads.** TCP delivers bytes, not messages. The frame reader reassembles frames across any number of
  reads, and a stream that ends in the middle of a frame is reported as a truncated frame.
* **Java object serialization is never used.** Payloads are plain JSON objects. Binary data (Core work units and
  partial results) is base64-encoded inside Core's `EncodedPayload`, with its size and CRC-32C.

## 2. Envelope

Every message has these fields:

| Field | Required | Description |
|---|---|---|
| `protocolVersion` | always | `"0.1"`. See [§9](#9-versioning). |
| `type` | always | One of the 11 types in [§3](#3-message-types). |
| `messageId` | always | Unique id chosen by the sender (a random UUID), 1–64 characters `[A-Za-z0-9._:-]`. |
| `inReplyTo` | optional | `messageId` of the message this one answers (`REGISTER_ACK`, `ERROR`). |
| `timestamp` | always | ISO-8601 instant when the sender created the message. **Informational only.** Durations never compare clocks of different machines. |
| `workerId` | worker messages | The sending worker (must equal the registered id), or the receiving worker on coordinator messages. |
| `experimentId` | optional | Copied from the task's `experimentId`. |
| `taskId` | attempt messages | Core task id. |
| `partitionId` | attempt messages | Core partition id, `<taskId>/p<index>`. |
| `attemptId` | attempt messages | `<partitionId>/a<attempt>`. A retry always has a new attempt id. |
| `payload` | always | JSON object; its schema depends on `type`. |

**Attempt messages** (`TASK`, `TASK_ACCEPTED`, `TASK_PROGRESS`, `RESULT`, `CANCEL`) must carry `taskId`,
`partitionId` and `attemptId`, consistently: the partition id must belong to the task, and the attempt id to the
partition. An `ERROR` carries all three ids or none. **Connection messages** (`REGISTER`, `REGISTER_ACK`,
`HEARTBEAT`, `HEARTBEAT_ACK`, `SHUTDOWN`) carry none.

Fields with no value are omitted. Receivers ignore fields they do not know, and read enum values they do not know
as `UNKNOWN`.

## 3. Message types

| Type | Direction | Payload | Purpose |
|---|---|---|---|
| `REGISTER` | worker → coordinator | `capabilities`, `proof` | First message on a connection |
| `REGISTER_ACK` | coordinator → worker | `sessionId`, `coordinatorId`, `runtimeVersion`, `heartbeatIntervalMillis`, `heartbeatTimeoutMillis`, `maxMessageBytes`, `coordinatorProof` | Registration accepted |
| `TASK` | coordinator → worker | `workUnit` (Core `WorkUnitMessage`), `timeoutMillis`, `priority` | Execute one attempt of one partition |
| `TASK_ACCEPTED` | worker → coordinator | `activeTasks`, `maxConcurrentTasks` | The worker admitted the attempt |
| `TASK_PROGRESS` | worker → coordinator | `completedUnits`, `totalUnits`, `unit` | Kernel progress (throttled) |
| `RESULT` | worker → coordinator | `partialResult` (Core `PartialResultMessage`), `timing` | Partial result of an attempt |
| `ERROR` | both | `code`, `message`, `retryable`, `fatal`, `taskError` (Core `ErrorInfo`, optional) | Rejection, failure or protocol violation |
| `HEARTBEAT` | worker → coordinator | `sequence`, `activeTasks`, `maxConcurrentTasks`, `freeMemoryBytes`, `systemLoadAverage`, `processCpuLoad`, `completedTasks`, `failedTasks`, `runningAttempts` | Liveness and load |
| `HEARTBEAT_ACK` | coordinator → worker | `sequence`, `workerStatus` | Heartbeat received; the worker's status at the coordinator |
| `CANCEL` | coordinator → worker | `reason`, `detail` | Stop an attempt |
| `SHUTDOWN` | both | `reason`, `drain`, `reconnect` | The sender is leaving |

### 3.1 Payload rules

| Payload | Rules (violations give `INVALID_PAYLOAD`) |
|---|---|
| `capabilities` | `schemaVersion` `0.1`; `hostname` 1–255 characters; `cpuCores` ≥ 1; `maxConcurrentTasks` 1–1024; `maxHeapBytes`, `maxTaskMemoryBytes` > 0; `maxMessageBytes` ≥ 1024; at most 1024 algorithms; each algorithm has an exact version; at most 32 labels (`[A-Za-z0-9._-]{1,64}` keys, values up to 256 characters); `gpu.available` only if Core detected a backend |
| `proof` | `scheme` `HMAC-SHA256`; `nonce` 16–64 base64url characters; `issuedAt` instant; `signature` 64 lowercase hex digits |
| `REGISTER_ACK` | `0 < heartbeatIntervalMillis < heartbeatTimeoutMillis`; `maxMessageBytes` ≥ 1024; `coordinatorProof` 64 hex digits |
| `TASK` | `timeoutMillis` ≥ 1; the work unit's partition id, task id and attempt number must equal the envelope's (otherwise `INVALID_MESSAGE`). Core validates the work unit itself (schema version, descriptor, payload). |
| `RESULT` | The partial result's task id, partition id and attempt must equal the envelope's, and its `workerId` must equal the sender (otherwise `INVALID_MESSAGE`). Timings are ≥ 0. |
| `ERROR` | `message` is truncated to 2000 characters and never contains stack traces |
| `HEARTBEAT` | `sequence` ≥ 1; `maxConcurrentTasks` 1–1024; at most 4096 `runningAttempts` |

### 3.2 Error codes

| `code` | Fatal | Meaning |
|---|---|---|
| `MALFORMED_MESSAGE` | no | Not a JSON object |
| `MESSAGE_TOO_LARGE` | yes | Frame exceeds the receiver's limit |
| `UNSUPPORTED_PROTOCOL_VERSION` | yes | Incompatible `protocolVersion` |
| `UNKNOWN_MESSAGE_TYPE` | no | Unknown `type` |
| `INVALID_MESSAGE` | no | Envelope field missing or malformed, or ids inconsistent |
| `INVALID_PAYLOAD` | no | Payload does not match its schema |
| `UNEXPECTED_MESSAGE` | no | Wrong direction, or not allowed in the connection's state |
| `DUPLICATE_MESSAGE` | no | Message id already seen (the message is dropped and logged, not answered) |
| `NOT_REGISTERED` | yes | A message other than `REGISTER` before registration |
| `AUTHENTICATION_FAILED` | yes | Invalid, stale or replayed proof |
| `REGISTRATION_REJECTED` | yes | Duplicate worker id, worker limit reached, or a fault-simulation worker the coordinator does not allow |
| `TASK_REJECTED` | no | The worker refuses an attempt (capacity, unsupported algorithm, invalid work unit, shutting down); `retryable` says whether another worker could run it |
| `TASK_FAILED` | no | Execution failed; `taskError` holds Core's `ErrorInfo` |
| `TASK_CANCELLED` | no | The attempt was cancelled, in reply to `CANCEL` |
| `UNKNOWN_TASK` | no | The message names an attempt the receiver does not know |
| `SHUTTING_DOWN` | no | The sender is shutting down |
| `INTERNAL_ERROR` | no | Unexpected error in the sender; details only in its log |
| `UNKNOWN` | — | A code from a newer peer |

`retryable` means that repeating the same request, possibly on another worker, could succeed. `fatal` means that
the sender closes the connection right after the message.

## 4. Authentication

Workers prove that they know the cluster's **shared secret** (at least 16 bytes), and the coordinator proves it
back. The secret never travels over the network.

1. The worker sends `REGISTER` with
   `proof.signature = hex(HMAC-SHA256(secret, "DCP-REGISTER\n" + protocolVersion + "\n" + workerId + "\n" + nonce + "\n" + issuedAtEpochMillis))`,
   with a fresh random `nonce` (18 bytes, base64url).
2. The coordinator recomputes the signature and compares it in constant time. It checks that `issuedAt` is within
   its clock-skew window (default ±5 minutes) and that the nonce has not been used within the window. It answers
   with `REGISTER_ACK` and
   `coordinatorProof = hex(HMAC-SHA256(secret, "DCP-REGISTER-ACK\n" + workerId + "\n" + nonce + "\n" + sessionId))`.
3. The worker verifies `coordinatorProof`. If it does not match, the worker closes the connection and executes
   nothing.

A failed proof gives `ERROR` `AUTHENTICATION_FAILED` and the connection is closed. Without TLS, an attacker who
can **modify** traffic on the network could still alter messages after registration. Enable TLS (`tls.enabled`)
on networks that are not trusted ([deployment.md](deployment.md)).

## 5. Limits and defaults

| Setting | Default | Where |
|---|---|---|
| Coordinator port | 7650 | coordinator |
| `maxMessageBytes` | 64 MiB | both sides |
| Registration timeout (connection without `REGISTER`) | 10 s | coordinator |
| Heartbeat interval | 2 s | coordinator, announced in `REGISTER_ACK` |
| Heartbeat timeout (worker declared unavailable) | 10 s | coordinator, announced in `REGISTER_ACK` |
| Remembered message ids per connection | 4096 | both sides |
| Protocol errors tolerated per connection | 10, then the connection is closed | coordinator |
| Clock-skew window for registration proofs | 5 min | coordinator |

## 6. Connection lifecycle

```
worker                                     coordinator
  |---- TCP connect ------------------------->|  state: CONNECTED (only REGISTER allowed)
  |---- REGISTER (capabilities, proof) ------>|  verify proof, capabilities and policy
  |<--- REGISTER_ACK (session, timing) -------|  state: REGISTERED
  |---- HEARTBEAT (every interval) ---------->|
  |<--- HEARTBEAT_ACK ------------------------|
  |<--- TASK ---------------------------------|  the scheduler chose this worker
  |---- TASK_ACCEPTED ----------------------->|
  |---- TASK_PROGRESS (throttled) ----------->|
  |---- RESULT ------------------------------>|  Core validates and classifies the partial result
  |              ...                          |
  |---- SHUTDOWN (drain) -------------------->|  no new tasks; running attempts finish
  |---- close ------------------------------->|
```

**Coordinator.** On a new connection, only `REGISTER` is accepted. Anything else gives `NOT_REGISTERED` and the
connection is closed. A connection that does not register within the timeout is closed. After registration, a
second `REGISTER` or a coordinator-only type is `UNEXPECTED_MESSAGE`. A `workerId` different from the
registered one is fatal. Repeated message ids are dropped. After 10 protocol errors the connection is closed.

**Worker.** It sends `REGISTER` immediately after connecting and waits for `REGISTER_ACK`. If the connection
fails or closes, it cancels its running attempts (their results could not be delivered), then reconnects with
exponential backoff and registers again with a new proof. On `SHUTDOWN` with `reconnect: true` (a coordinator
restart), it reconnects. With `reconnect: false`, it stops.

## 7. Attempts

* The coordinator sends `TASK` only to workers with a free slot (`activeTasks < maxConcurrentTasks`).
* The worker validates the work unit (algorithm installed, Core payload valid, attempt not already known). It
  replies `TASK_ACCEPTED`, or `ERROR` `TASK_REJECTED` with `retryable` set.
* While running, the worker may send throttled `TASK_PROGRESS` messages. It ends the attempt with exactly one
  `RESULT`, one `ERROR` `TASK_FAILED` or one `ERROR` `TASK_CANCELLED`.
* `CANCEL` is cooperative: Core kernels poll a cancellation token. The worker confirms with `TASK_CANCELLED`, or
  with `RESULT` if the kernel finished first. The coordinator keeps the worker's slot occupied until then.
* `timeoutMillis` in `TASK` is the attempt's budget. The worker cancels the kernel when it is exceeded. The
  coordinator enforces the same timeout on its side, so a hung worker cannot hold an attempt for ever.
* `HEARTBEAT.runningAttempts` lists the admitted attempts. Messages on a connection are ordered. If an attempt
  that the coordinator saw accepted is missing from a later heartbeat, and no `RESULT` or `ERROR` arrived, the
  worker has lost it. The coordinator treats it as failed and may retry it.

## 8. Timing fields

All durations are nanoseconds measured on **one** machine's monotonic clock.

| Field | Clock | Measures |
|---|---|---|
| `timing.queueNanos` | worker | Admission to start of execution |
| `timing.executeNanos` | worker | Decoding the work unit, the kernel, encoding the result |
| `partialResult.computeNanos` | worker | The kernel only (Core) |
| `timing.residenceNanos` | worker | Receiving the `TASK` frame to having the result ready to send |

The coordinator measures the round trip (`TASK` handed to the connection → `RESULT` frame received). Then
`transfer = roundTrip − residenceNanos` is the time spent in transport (both directions, including socket
buffering). It is reported separately from computation ([integration-contract.md](integration-contract.md)).

## 9. Versioning

`protocolVersion` is `MAJOR.MINOR`. A receiver rejects a different MAJOR. While MAJOR is `0` (draft), MINOR must
match too. Within a compatible version, senders may add optional fields, and receivers ignore unknown fields.
The capability object has its own `schemaVersion`, with the same rules. Core objects inside payloads
(`WorkUnitMessage`, `PartialResultMessage`, `ErrorInfo`) carry Core's `schemaVersion` and follow Core's rules.

## 10. Examples

Produced by the protocol codec (`MessageCodec`) from real Core work units and results, then shortened: base64
is cut with `…`, and the `REGISTER` example lists three of the ten algorithms.

### `REGISTER`

```json
{
  "protocolVersion": "0.1",
  "type": "REGISTER",
  "messageId": "1b1689fe-2f41-43cb-a196-fbf5d40f013a",
  "timestamp": "2026-11-22T15:07:00.870441357Z",
  "workerId": "lab-pc-07",
  "payload": {
    "capabilities": {
      "schemaVersion": "0.1",
      "hostname": "lab-pc-07",
      "osName": "Linux",
      "osArch": "amd64",
      "javaVersion": "21",
      "runtimeVersion": "0.1.0-SNAPSHOT",
      "coreVersion": "0.1.0-SNAPSHOT",
      "cpuCores": 4,
      "maxHeapBytes": 1073741824,
      "totalMemoryBytes": -1,
      "maxConcurrentTasks": 4,
      "maxTaskMemoryBytes": 268435456,
      "maxMessageBytes": 16777216,
      "algorithms": [
        { "algorithm": { "id": "matrix.multiply", "version": "1.0.0" }, "modes": [ "DISTRIBUTED_CPU" ] },
        { "algorithm": { "id": "primes.count", "version": "1.0.0" }, "modes": [ "DISTRIBUTED_CPU" ] },
        { "algorithm": { "id": "sort.merge", "version": "1.0.0" }, "modes": [ "DISTRIBUTED_CPU" ] }
      ],
      "gpu": { "available": false, "backends": [ ], "unavailableReasons": [ "no GPU backend" ] },
      "labels": { "rack": "lab-2" },
      "faultSimulation": false
    },
    "proof": {
      "scheme": "HMAC-SHA256",
      "nonce": "ZBcEPgOe4W55mQxW2mj5NKYC",
      "issuedAt": "2026-11-22T15:07:00.818Z",
      "signature": "4d012e968b54ba959664dd8b500bf6e937072fb7d05b67d1982ab3500752ccf7"
    }
  }
}
```

(The real message lists all 10 distributable Core algorithms.)

### `REGISTER_ACK`

```json
{
  "protocolVersion": "0.1",
  "type": "REGISTER_ACK",
  "messageId": "0e7c9243-4ed5-4280-a219-6fbd0062bd54",
  "inReplyTo": "1b1689fe-2f41-43cb-a196-fbf5d40f013a",
  "timestamp": "2026-11-22T15:07:00.878821704Z",
  "workerId": "lab-pc-07",
  "payload": {
    "sessionId": "0b8e5a1e-6a2f-4c43-9d8e-0f3a2b1c4d5e",
    "coordinatorId": "coordinator@lab-server",
    "runtimeVersion": "0.1.0-SNAPSHOT",
    "heartbeatIntervalMillis": 2000,
    "heartbeatTimeoutMillis": 10000,
    "maxMessageBytes": 67108864,
    "coordinatorProof": "cadf1dfb42a4bd32051ce069187f4f96a64fbc220fcae8655c2f95f6fd97c2bf"
  }
}
```

### `TASK`

```json
{
  "protocolVersion": "0.1",
  "type": "TASK",
  "messageId": "0376d424-550b-4040-8f28-c9252ae138fc",
  "timestamp": "2026-11-22T15:07:00.883474618Z",
  "workerId": "lab-pc-07",
  "experimentId": "exp-primes",
  "taskId": "task-42",
  "partitionId": "task-42/p1",
  "attemptId": "task-42/p1/a1",
  "payload": {
    "workUnit": {
      "descriptor": {
        "schemaVersion": "0.1",
        "partitionId": "task-42/p1",
        "taskId": "task-42",
        "algorithm": { "id": "primes.count", "version": "1.0.0" },
        "index": 1,
        "count": 4,
        "bounds": { "type": "RANGE", "start": 250001, "endExclusive": 500001 },
        "resultType": "prime-count",
        "retryable": true
      },
      "attempt": 1,
      "payload": { "format": "primes-range/1", "sizeBytes": 42, "crc32c": "48674bc7", "base64": "RENQMQ4AAABw…" }
    },
    "timeoutMillis": 60000,
    "priority": "NORMAL"
  }
}
```

### `TASK_ACCEPTED`, `TASK_PROGRESS`

```json
{ "protocolVersion": "0.1", "type": "TASK_ACCEPTED", "messageId": "cfd03553-3eae-422c-abee-3483102d4151",
  "timestamp": "2026-11-22T15:07:00.883903425Z", "workerId": "lab-pc-07", "experimentId": "exp-primes",
  "taskId": "task-42", "partitionId": "task-42/p1", "attemptId": "task-42/p1/a1",
  "payload": { "activeTasks": 1, "maxConcurrentTasks": 4 } }
```

```json
{ "protocolVersion": "0.1", "type": "TASK_PROGRESS", "messageId": "a7d9dc55-7e22-4208-a70f-ade19eedf899",
  "timestamp": "2026-11-22T15:07:00.884027771Z", "workerId": "lab-pc-07", "experimentId": "exp-primes",
  "taskId": "task-42", "partitionId": "task-42/p1", "attemptId": "task-42/p1/a1",
  "payload": { "completedUnits": 131072, "totalUnits": 250000, "unit": "numbers" } }
```

### `RESULT`

```json
{
  "protocolVersion": "0.1",
  "type": "RESULT",
  "messageId": "d2460271-51e3-4cd2-acdc-8d08435e4556",
  "timestamp": "2026-11-22T15:07:00.884142679Z",
  "workerId": "lab-pc-07",
  "experimentId": "exp-primes",
  "taskId": "task-42",
  "partitionId": "task-42/p1",
  "attemptId": "task-42/p1/a1",
  "payload": {
    "partialResult": {
      "schemaVersion": "0.1",
      "taskId": "task-42",
      "partitionId": "task-42/p1",
      "index": 1,
      "attempt": 1,
      "resultType": "prime-count",
      "payload": { "format": "primes-count/1", "sizeBytes": 50, "crc32c": "48674bc7", "base64": "RENQMQ4AAABw…" },
      "computeNanos": 5009252,
      "workerId": "lab-pc-07"
    },
    "timing": { "queueNanos": 41000, "executeNanos": 2350000, "residenceNanos": 2610000 }
  }
}
```

> **Note on `crc32c`.** Core's binary payload already ends with its own CRC-32C. The CRC-32C of data followed by
> its own CRC is a constant (`48674bc7`), so this field has the same value for every valid payload. Integrity is
> still checked: a corrupted payload no longer produces the constant.

### `ERROR`

```json
{ "protocolVersion": "0.1", "type": "ERROR", "messageId": "bc6d3eec-84fb-4be0-90db-41fe968a0edd",
  "inReplyTo": "0376d424-550b-4040-8f28-c9252ae138fc", "timestamp": "2026-11-22T15:07:00.884481522Z",
  "workerId": "lab-pc-07", "experimentId": "exp-primes", "taskId": "task-42", "partitionId": "task-42/p1",
  "attemptId": "task-42/p1/a1",
  "payload": { "code": "TASK_REJECTED", "message": "Worker is at its limit of 4 concurrent tasks",
               "retryable": true, "fatal": false } }
```

### `HEARTBEAT`, `HEARTBEAT_ACK`

```json
{ "protocolVersion": "0.1", "type": "HEARTBEAT", "messageId": "54d86c33-1fc4-4f27-9e5a-cc2dc7759226",
  "timestamp": "2026-11-22T15:07:00.884941962Z", "workerId": "lab-pc-07",
  "payload": { "sequence": 12, "activeTasks": 1, "maxConcurrentTasks": 4, "freeMemoryBytes": 812345678,
               "systemLoadAverage": 0.42, "processCpuLoad": 0.25, "completedTasks": 37, "failedTasks": 0,
               "runningAttempts": [ "task-42/p1/a1" ] } }
```

```json
{ "protocolVersion": "0.1", "type": "HEARTBEAT_ACK", "messageId": "e3d15784-85c2-4317-bee7-a2cbc821a42d",
  "timestamp": "2026-11-22T15:07:00.885025721Z", "workerId": "lab-pc-07",
  "payload": { "sequence": 12, "workerStatus": "ACTIVE" } }
```

### `CANCEL`, `SHUTDOWN`

```json
{ "protocolVersion": "0.1", "type": "CANCEL", "messageId": "6a5390a0-4044-469c-9145-b862c4492bdf",
  "timestamp": "2026-11-22T15:07:00.885073575Z", "workerId": "lab-pc-07", "experimentId": "exp-primes",
  "taskId": "task-42", "partitionId": "task-42/p1", "attemptId": "task-42/p1/a1",
  "payload": { "reason": "TIMEOUT", "detail": "attempt exceeded 60000 ms" } }
```

```json
{ "protocolVersion": "0.1", "type": "SHUTDOWN", "messageId": "e859c24d-b1dc-40f3-a041-b1a93506d446",
  "timestamp": "2026-11-22T15:07:00.885399838Z", "workerId": "lab-pc-07",
  "payload": { "reason": "operator stopped the worker", "drain": true, "reconnect": false } }
```
