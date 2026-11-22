# DistriCompute-Core — Implementation Plan

This plan follows the phase order from the project brief. Each phase ends with these steps: build, run the
tests, review, fix defects, update the documentation, and commit only completed work. The status column
records only what has actually been done. It is never filled in ahead of time.

## 1. Phases, deliverables and acceptance criteria

| Phase | Deliverables | Acceptance criteria |
|---|---|---|
| **1. Architecture and foundation** | Architecture review ([architecture.md](architecture.md)); this plan; draft [integration contract](integration-contract.md); Maven build (Java 21, JUnit 5, Jackson, SLF4J); code-quality checks (Checkstyle, SpotBugs, Enforcer, JaCoCo); GitHub Actions CI; README, CONTRIBUTING, LICENSE | `./mvnw verify` succeeds from a clean checkout; the CI workflow runs the build, unit tests, concurrency tests and quality checks as separate steps |
| **2. Task model and algorithm interfaces** | `TaskSpec`, `TaskStatus` state machine with permitted transitions, execution constraints, priority; algorithm id and version (SemVer) and descriptor; parameter schema and validation; `ComputeAlgorithm` SPI; `AlgorithmRegistry`; error model; JSON codec with schema-version checks | Invalid transitions are rejected; invalid parameters produce field-level violations; JSON round-trips are lossless; unknown major schema versions are rejected; the registry rejects duplicate ids |
| **3. Sequential baselines** | Prime counting (segmented sieve), merge sort, matrix addition, matrix multiplication (cache-blocked); deterministic input generators; workload analysis and memory guard; verifiers (independent prime-counting function, sortedness and permutation fingerprint, exact recomputation, Freivalds with a floating-point error bound) | Results match independent oracles (known π(10^k) values, `Arrays.sort`, naive triple loop); every verifier detects a deliberately corrupted result; oversized requests are rejected before allocation |
| **4. Local parallel execution** | Bounded worker pool (fixed threads, bounded in-flight work), controlled submission, completion queue, cooperative cancellation, timeouts, exception propagation, orderly shutdown, progress events, phase timers | Concurrency tests: exact chunk coverage, failures propagate and cancel siblings, cancellation and timeouts terminate promptly, no thread leaks after shutdown, concurrent task submissions are isolated |
| **5. Partitioning and aggregation** | Partition model (id, parent task, index, bounds, expected result type, status, metadata); equal-size remainder-aware range, chunk and 2-D block strategies; `PartitionedAlgorithm` for the P1 algorithms; `PartialResultCollector` (valid, duplicate, invalid, missing, failed); binary payload codecs; coordinator/worker facade; `MULTITHREADED_CPU` through partitions | No element lost or duplicated for any configuration; plans are deterministic; partitioned results equal sequential results (bitwise where the order is identical); codec round-trip and corruption tests pass |
| **6. Matrix and numerical algorithms** | Configurable block sizes; numerical integration, polynomial evaluation, root finding (bracketing), Monte Carlo; data operations (search, filter, aggregation and statistics) | Analytic or independent oracles with documented tolerances; limitations of iterative algorithms documented |
| **7. Benchmarks and metrics** | Environment capture; warm-ups and repeated trials; median, min, max, mean and standard deviation; speedup and efficiency against a valid sequential baseline; JSON, CSV and Markdown output; benchmark suite files; [performance-methodology.md](performance-methodology.md) | Statistics unit-tested against hand-computed values; reports contain raw samples and environment details; nothing is reported without a run |
| **8. GPU abstraction and strategy assessment** | GPU backend SPI and capability detection; documented integration path; `StrategyAdvisor` that evaluates modes against a supplied resource snapshot | GPU is reported unavailable when no backend is detected; the advisor marks unsupported and unsuitable modes with reasons; small workloads recommend sequential |
| **9. Integration with Contributor 2** | Agreed contract; tagged release; joint integration tests (in the Distributed and Application repositories) | Distributed executes Core partitions from bytes and reproduces the sequential results |
| **10. Integration tests and documentation** | End-to-end tests (JSON task → JSON result); examples; release notes; implementation report | The CI run on GitHub is green; documentation matches the code |

## 2. First independently testable milestone (M1)

**M1 = Phases 1–3 plus a minimal `LocalExecutionEngine` in `SEQUENTIAL_CPU` mode.** A clean checkout runs
`./mvnw verify`, which:

* builds the library with no dependency on the other repositories, the network or a GPU;
* executes all four priority-1 algorithms from a `TaskSpec` (JSON or Java) through the full local
  lifecycle (`CREATED → … → COMPLETED`);
* verifies every result with an independent method and rejects invalid inputs with structured errors.

M1 needs no agreement from Contributors 2 or 3, because it defines nothing they depend on beyond the draft
contract, and that contract is versioned.

## 3. Status

| Phase | Status | Notes |
|---|---|---|
| 1 | Done | `./mvnw verify` passes locally (enforcer, `-Werror` compile, tests, JaCoCo, Checkstyle, SpotBugs). The CI workflow is committed; its first GitHub run is reported in the implementation report. |
| 2 | Done | Task model, lifecycle state machine, parameter schema and validator, algorithm SPI, registry with derived capabilities, error model, JSON codec. 54 unit and 2 concurrency tests pass. |
| 3 | Done | Prime counting, merge sort, matrix addition, cache-blocked matrix multiplication with independent verifiers; deterministic generators; workload profiles; memory guard; `LocalExecutionEngine` (sequential) driving the full lifecycle. **Milestone M1 reached.** 101 unit and 2 concurrency tests pass. |
| 4–10 | Not started | — |

## 4. Postponed features

These are deliberately **not** implemented early. Each one either belongs to another contributor or would be
premature.

| Feature | Reason | Revisit when |
|---|---|---|
| A real GPU backend (JOCL, JCuda, TornadoVM, …) | No GPU in the development or CI environment, so it can't be validated; the binding choice affects the build and deployment of all repositories | Hardware is available and the team agrees on a binding (Phase 8+) |
| Hybrid CPU/GPU and distributed execution | Depends on a working GPU backend and on Distributed scheduling | After GPU and distributed execution both work |
| Network transport, worker registration, heartbeats, remote retry | Contributor 2's responsibility | — (never in Core) |
| Persistence of experiments, REST API, GUI, reports | Contributor 3's responsibility | — (never in Core) |
| Dataset upload and references (`DatasetReference` inputs) | Storage belongs to the Application; generated and inline inputs cover the demonstration | When the Application defines dataset storage |
| Strassen, Winograd or other numerically different matrix algorithms | Different rounding behaviour complicates verification; the baseline must be measured first | After block-size benchmarks exist |
| Dynamic (on-demand) partition generation across nodes | The static plan is enough for the demonstration; the interface allows it later | If Distributed needs work stealing |
| Priority 3 algorithms (image and signal processing, simulations) | Priority order in the shared rules | After P1 and P2 are complete and integrated |
| Arbitrary expression parsing for numerical functions | Unbounded scope and a safety risk; supported functions are an explicit, extensible catalog | Only if a safe, bounded grammar is agreed |
