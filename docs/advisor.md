# Execution Strategy Advisor

The advisor answers "how should this task run?" before it runs (`POST /api/v1/tasks/analyze`, the advice step
of the task workspace). It does not estimate anything itself:

1. **Core** analyses the workload (`analyze`: operations, memory, input and output size, partitionability) and
   assesses every execution mode with its cost model (`StrategyAdvisor`).
2. **Distributed** supplies the resources that model needs (`Coordinator.advise`): the workers that can run the
   algorithm now, their cores and slots, the busy slots, the measured network, and calibrations from completed
   runs.
3. **The application** adds its own measurements of identical workloads, the basis and confidence of each
   estimate, its configured limits, and warnings.

## 1. What is shown

| Item | Source |
|---|---|
| Workload characteristics, estimated operations, memory, partitionability | Core `WorkloadProfile` |
| Per mode: verdict (`RECOMMENDED`, `SUITABLE`, `NOT_RECOMMENDED`, `UNSUPPORTED`…), threads or cores, estimated seconds, reasons | Core `StrategyAssessment` |
| Whether each mode can run here (`executable`) | Distributed (`executableModes`) and Core's supported modes. GPU and hybrid are never executable, because neither component implements them |
| Available and ineligible workers (with reasons), slots, pending partitions, GPU status | Distributed `RuntimeContext`, Core `GpuStatus` |
| Estimated communication: bytes a distributed run would move, and seconds at the measured rate | Distributed; seconds only once the network has been measured |
| Measured times of identical workloads (same algorithm and dimensions) per mode: runs, median, minimum, maximum | Application database |
| Assumptions | Core |
| Warnings | Distributed notes plus application checks (no eligible worker, network not measured, very small workload, measurements from other configurations) |

## 2. Basis and confidence

Each mode's estimate has a **basis**:

| Basis | Meaning |
|---|---|
| `MEASURED` | This application has completed runs of the same algorithm with the same dimensions in this mode; the measured median is shown next to Core's estimate |
| `HISTORICAL` | Core's estimate is calibrated with measured throughput (from earlier runs, which the application gives the coordinator at startup, or from the coordinator's own measurements) |
| `THEORETICAL` | Core's cost model without measurements |
| `NONE` | No estimate |

The **confidence** of the recommendation:

| Confidence | Rule |
|---|---|
| `HIGH` | At least 3 measured runs of an identical workload in the recommended mode |
| `MEDIUM` | Some measured runs, or a calibrated estimate |
| `LOW` | A theoretical estimate only |
| `NONE` | No suitable mode, or neither an estimate nor a measurement for it. The advice then says that **a reliable recommendation cannot be made yet** and recommends nothing |

Measured results, estimates and theoretical figures are always labelled as such. No mode is ever claimed to be
optimal: Core lists its assumptions, and the resources describe the cluster only at the moment of the advice.

## 3. Overrides

The user may choose any executable mode. When an experiment is created, the application computes the
recommendation again and stores it with the experiment (`recommended_mode`), together with whether the user
followed it (`recommendation_accepted`), the user's reason (`override_reason`) and the basis (`advice_basis`). The
runtime records the override as well (Distributed `STRATEGY_OVERRIDE` event).
