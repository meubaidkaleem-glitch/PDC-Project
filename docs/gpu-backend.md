# GPU Backend: Status, Options and Integration Path

## Status in this release

**There is no GPU execution.** Core ships the `GpuBackend` SPI, capability detection (`GpuStatus.detect`), and the
wiring that advertises `GPU` for an algorithm only when a detected backend supports it. No backend
implementation ships:

* the development and CI environments have no GPU (GitHub-hosted runners have none), so a backend could not be
  validated;
* the rule is that an operation is never labelled GPU-accelerated unless it really runs on a GPU.

What this means in practice:

* `AlgorithmCapabilities.gpu` is `false` for every algorithm, and `GPU` is never in `supportedModes`.
* A task requesting `GPU` fails with `UNSUPPORTED_EXECUTION_MODE` and the detection summary, for example "No GPU
  backend is installed".
* The strategy advisor marks `GPU` (and `HYBRID`) as `UNSUPPORTED` and gives the reason.
* The application stays fully functional without a GPU.

Tests cover detection with no backend, a backend whose native library is missing (`UnsatisfiedLinkError` becomes
an unavailability reason, never a crash), and a detection-only test double. The test double shows that `GPU` is
advertised only for the algorithms a backend supports. The double executes nothing and exists only in test code.

## Java GPU technologies considered

| Option | GPUs | Integration cost | Testable without a GPU | Notes |
|---|---|---|---|---|
| **JOCL** (OpenCL bindings) | NVIDIA, AMD, Intel; also CPUs | Low: Maven artifact with bundled native libraries; kernels written in OpenCL C | **Yes**, against POCL (a portable OpenCL implementation on the CPU) | Vendor-neutral; fp64 needs `cl_khr_fp64` |
| JCuda (CUDA bindings) | NVIDIA only | Medium: native libraries must match the CUDA version | No | Mature; cuBLAS available through JCublas |
| TornadoVM | NVIDIA, AMD, Intel (OpenCL, PTX, SPIR-V) | High: its own runtime, JVM flags and installation | Partly | Compiles Java methods to GPU code; heavy for the other repositories' builds |
| Aparapi | OpenCL | Medium | Partly | Limited maintenance |
| Panama FFM + `jextract` (CUDA or OpenCL C APIs) | Any with a C API | Medium | No | FFM is final only in Java 22+; Core targets Java 21 |
| Project Babylon / HAT | Research | — | — | OpenJDK incubation, not production-ready |

**Recommendation: JOCL first, for `matrix.multiply` first.** It is vendor-neutral, adds only a Maven dependency,
and its code path can be tested in CI with POCL on the CPU. Results from POCL validate correctness, not GPU
performance.

## Integration path (future work)

1. Add an optional module or artifact `districompute-core-gpu-jocl`, so the core library gains no native
   dependency. It registers `JoclGpuBackend` in `META-INF/services/org.districompute.core.resource.GpuBackend`.
2. `probe()`: enumerate OpenCL platforms and devices. Require fp64 (`cl_khr_fp64`), because Core's matrices are
   `double`. Report `globalMemoryBytes`. Turn every failure into a reason.
3. `execute(matrix.multiply)`:
   * check that `A + B + C` fits in device memory (otherwise use tiled transfers or decline);
   * copy A and B to the device;
   * run a tiled DGEMM kernel;
   * copy C back;
   * report **transfer to device**, **kernel** and **transfer back** times separately, so transfer overhead is
     never hidden. One-time **initialization** (context creation, kernel compilation) must be measured and
     reported separately as well.
4. Engine dispatch for `GPU` mode: run the backend, then verify with the existing CPU verifier (Freivalds with
   the floating-point error bound). GPU results are not bitwise identical to the CPU kernel: the summation order
   differs and fused multiply-add may be used. Verification therefore relies on the error bound, not on the
   digest.
5. Fallback: if no backend supports the algorithm, the advisor never recommends `GPU`, and an explicit `GPU`
   request fails with `UNSUPPORTED_EXECUTION_MODE`. It is never silently run on the CPU under a GPU label.
6. Benchmark separately: a `matmul-gpu` suite comparing `SEQUENTIAL_CPU`, `MULTITHREADED_CPU` and `GPU`
   (including transfer time) across sizes. Small matrices are expected to lose on the GPU because of transfer and
   launch overhead. This has to be shown by measurement, not assumed.

## Hardware and runtime requirements (for the future backend)

* An OpenCL 1.2+ device with `cl_khr_fp64`, and its vendor driver (NVIDIA, AMD or Intel).
* For CI: POCL installed on the runner (functional tests only).
* Device memory of at least `8·(m·k + k·n + m·n)` bytes for a single-pass product.
