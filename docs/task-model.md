# Task Model

This page describes how DistriCompute-Core represents a computational task, validates it, and tracks it through
its lifecycle. The JSON formats are part of the [integration contract](integration-contract.md).

## 1. Task specification (`TaskSpec`)

`org.districompute.core.task.TaskSpec` is an immutable record that the application creates for every user
submission.

| Requirement (project brief) | Field or type |
|---|---|
| Unique task ID | `taskId`: 1–128 characters from `[A-Za-z0-9._:-]`; a random UUID when built with `TaskSpec.builder` |
| Experiment ID | `experimentId` (optional) |
| Algorithm identifier and version | `algorithm`: an `AlgorithmRef` (`AlgorithmId`, optional `AlgorithmVersion`) |
| Input representation | `parameters`: algorithm-specific values validated against the algorithm's parameter schema. Generated inputs are described by dimensions, `seed` and `distribution`; small inline inputs use array parameters |
| Input metadata, workload dimensions, estimated complexity and memory | Produced during validation as a `WorkloadProfile` (`dimensions`, `estimatedOperations`, `timeComplexity`, `inputBytes`, `estimatedPeakMemoryBytes`, …) |
| Requested execution constraints | `constraints`: `ExecutionConstraints` (`mode`, `maxThreads`, `partitioning`, `timeoutMillis`, `maxMemoryBytes`) |
| Task priority | `priority`: `TaskPriority` (`LOW`, `NORMAL`, `HIGH`) |
| Creation timestamp | `createdAt` |
| Validation status, execution status | `TaskLifecycle` (current `TaskStatus` and its history). The validation outcome is reported as `ErrorInfo` with violations, or as the `VALIDATED` status |
| Schema versioning | `schemaVersion`, checked on construction (`SchemaVersion.requireCompatible`) |

```java
TaskSpec task = TaskSpec.builder("matrix.multiply")
        .experimentId("exp-matmul-scaling")
        .parameter("rowsA", 1024).parameter("colsA", 1024).parameter("colsB", 1024)
        .mode(ExecutionMode.MULTITHREADED_CPU).maxThreads(4)
        .timeoutMillis(600_000)
        .build();
String json = ContractJson.toJson(task);              // explicit JSON format
TaskSpec again = ContractJson.fromJson(json, TaskSpec.class);   // equal to task
```

**Normalization.** Parameter values are converted to a JSON-compatible model: `Long`, `Double`, `Boolean`,
`String`, and lists of those (`ParameterValues`). A task therefore compares equal before and after a JSON round
trip. `null` values are rejected. To leave a parameter out, omit it.

## 2. Lifecycle (`TaskStatus`, `TaskLifecycle`)

```
CREATED → VALIDATING → VALIDATED → PLANNED → QUEUED → RUNNING → AGGREGATING → VERIFYING → COMPLETED
   (any non-terminal status) → FAILED | CANCELLED
```

* `TaskStatus.canTransitionTo` defines the permitted transitions. `TaskLifecycle` enforces them under a lock and
  records a timestamped history.
* Invalid transitions throw `DistriComputeException(INVALID_STATE_TRANSITION)`. `tryTransitionTo` returns `false`
  instead of throwing; use it where races are expected, for example cancellation arriving after completion.
* `COMPLETED` is reachable **only from `VERIFYING`**. Even a single-threaded run goes through `AGGREGATING` and
  `VERIFYING`. A task is never reported complete just because its threads stopped.
* `AGGREGATING` and `VERIFYING` are refinements of the brief's example list. They make two mandatory stages
  visible, as the shared rule requires ("every stage must have an identifiable status").

## 3. Parameter schema and validation

Every algorithm publishes a list of `ParameterSpec` entries: name, type, description, required flag, default,
inclusive bounds, allowed values, array length bounds and unit. The application can render forms from it.
`ParameterValidator.validate(schema, rawValues)` returns typed `Parameters` or throws a `ValidationException` that
lists **every** violation, each with a field path and a code.

| Type | Accepted input | Rejected |
|---|---|---|
| `INTEGER` | integers, integral decimals (`7.0`, `1e3`), numeric strings | fractions, booleans, values beyond 64 bits (`ABOVE_MAXIMUM`) |
| `DECIMAL` | finite numbers, numeric strings | NaN and infinities (`NOT_FINITE`) |
| `BOOLEAN` | booleans, `"true"`/`"false"` (any case) | anything else |
| `ENUM` | an allowed value (case-insensitive, stored in its canonical spelling) | other values (`NOT_ALLOWED`) |
| `INTEGER_ARRAY`, `DECIMAL_ARRAY` | lists whose elements follow the scalar rules | wrong length (`TOO_SHORT`, `TOO_LONG`), bad elements (reported as `name[i]`) |

Unknown parameters are rejected (`UNKNOWN_PARAMETER`), so typos fail loudly. Cross-field rules, such as matching
matrix dimensions, live in `ComputeAlgorithm.validate`.

## 4. Algorithm SPI and registry

`ComputeAlgorithm<I, R>` is the extension point:

| Step | Method | Notes |
|---|---|---|
| Describe | `descriptor()` | Id, version, name, category, parameter schema, result type, verification method, limitations |
| Validate | `validate(Parameters)` | Cross-field rules; schema validation happens before this |
| Analyse | `analyze(Parameters)` | `WorkloadProfile` computed **without allocating the input** |
| Prepare | `prepareInput(Parameters, ExecutionContext)` | Deterministic, so the same input can be rebuilt on any node |
| Compute | `computeSequential(I, ExecutionContext)` | Reference implementation and speedup baseline |
| Verify | `verify(I, R, ExecutionContext)` | Independent check (`VerificationReport`) |
| Summarize | `summarize(R)` | `ResultSummary` with a SHA-256 digest of the canonical result encoding |

Adding an algorithm requires no engine change. Implement the SPI and list an `AlgorithmProvider` in
`META-INF/services/org.districompute.core.registry.AlgorithmProvider`. `AlgorithmRegistry.loadDefault()`
discovers it. The registry:

* rejects duplicate ids and defaults that violate their own schema;
* resolves versions: same MAJOR, registered MINOR at least the requested MINOR;
* derives `AlgorithmCapabilities` from the implemented interfaces. An algorithm cannot claim a mode it doesn't
  implement.

## 5. Errors

Every Core failure is a `DistriComputeException` with an `ErrorCode`. Use `toErrorInfo(status)` to get the
serializable `ErrorInfo`. The codes and their retryability are listed in the
[integration contract §9](integration-contract.md#9-error-model--errorinfo).
`DistriComputeException.findIn(throwable)` recovers the original error from executor wrappers such as
`ExecutionException`.

## 6. JSON

`ContractJson` (Jackson 3, hidden behind a string and byte API) writes `lowerCamelCase` fields in declaration
order and ISO-8601 instants, and omits `null`s. When reading, it ignores unknown fields, enforces `schemaVersion`
compatibility, and reports malformed documents as `VALIDATION_FAILED` with code `MALFORMED_DOCUMENT`. Java object
serialization is never used.
