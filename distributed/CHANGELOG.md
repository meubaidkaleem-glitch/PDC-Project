# Changelog

All notable changes to DistriCompute-Distributed. The project is pre-release (`0.1.0-SNAPSHOT`): the protocol
(version `0.1`) and the Java API may still change after review by the other contributors.

## [Unreleased] — 0.1.0

### Added

* **Protocol** (`b209326`): versioned newline-delimited JSON over TCP with 11 message types, size-limited framing,
  validation with specific error codes, a duplicate-message filter, and mutual HMAC-SHA256 registration with replay
  protection. Optional TLS.
* **Coordinator and registry** (`43891c5`): TCP server, per-connection reader and writer with bounded queues,
  a single-threaded control loop, worker registry with capabilities, status, load and performance history.
* **Worker process** (`d137e0e`): capability detection, execution of Core partitions on a bounded pool, progress,
  heartbeats, attempt timeouts, cancellation, reconnection with backoff, graceful drain.
* **Dispatch and result collection** (`00da46b`): partitions and attempts, bounded priority queues, dynamic
  assignment to free slots, result validation through Core, completion with Core aggregation and verification.
* **Scheduling policies** (`90e2044`): round robin, least loaded, capability-aware, performance-aware and dynamic,
  with a recorded explanation for every assignment.
* **Fault tolerance** (`a62b8be`): attempt and acceptance timeouts, task deadlines, heartbeat reconciliation,
  quarantine, explicit retry policy, coordinator shutdown with worker reconnection.
* **Metrics, events and HTTP API** (`309ea1d`): task metrics separating computation, communication and scheduling;
  coordinator metrics; event bus with replay; HTTP API with Server-Sent Events.
* **Fault simulation** (`148cf87`): explicitly enabled fault profiles on real workers (slowdown, delays, link
  latency, disconnection, stopped heartbeats, hanging, failing, corrupt and duplicate results, capacity changes).
* **MPI demonstrations** (`c4bc7b3`): point-to-point, broadcast, scatter and gather, and parallel prime counting in
  C, with a run script and a CI job.
* **Application integration** (`0c6e724`): execution advice from Core's strategy advisor with runtime resources,
  local sequential and multithreaded modes through Core, recorded manual overrides, calibrations, `submit`,
  `advise` and `status` clients.
* **Release preparation** (Phase 12): `bench` command and benchmark results, deployment guide, start scripts,
  local multi-worker demonstration, example configurations and tasks, implementation report.

### Fixed

* `309ea1d`: a heartbeat built before an acknowledgement could be sent after it, so a running attempt looked lost
  (a CI failure on `a62b8be`). Heartbeats are now built atomically with acknowledgements, and the coordinator
  requires two consecutive omissions.
* `6256429`: a draining worker could close its connection before its last result was sent (seen in CI on
  `148cf87` and `c4bc7b3`).
* `a0a6b4a`, `3bf1a14` and Phase 12: the MPI demonstrations did not start with Open MPI 5 on the latest Ubuntu
  image. The causes, found one after another in CI, were a missing slot declaration, a version check that failed
  because the distribution package lacks Open MPI's help files, and Open MPI 5's default process binding.
