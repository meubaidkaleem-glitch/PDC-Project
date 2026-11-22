# Algorithms

This page covers every algorithm registered in DistriCompute-Core: what it computes, its parameters, how it
generates inputs and verifies results, and its known limitations. The catalog is also available at runtime
(`AlgorithmRegistry.loadDefault().catalog()`), serialized as JSON with the full parameter schema.

| Id | Version | Category | Modes implemented | Verification |
|---|---|---|---|---|
| `primes.count` | 1.0.0 | Number theory | sequential, multithreaded, distributed-compatible (`RANGE`) | Independent algorithm (exact) |
| `sort.merge` | 1.0.0 | Sorting | sequential, multithreaded, distributed-compatible (`CHUNK`) | Sortedness and multiset fingerprint |
| `matrix.add` | 1.0.0 | Linear algebra | sequential, multithreaded, distributed-compatible (`BLOCK_2D`) | Exact recomputation |
| `matrix.multiply` | 1.0.0 | Linear algebra | sequential, multithreaded, distributed-compatible (`BLOCK_2D`) | Freivalds with a floating-point error bound |
| `numeric.integrate` | 1.0.0 | Numerical | sequential, multithreaded, distributed-compatible (`RANGE`) | Closed-form antiderivative within error bounds |
| `numeric.polynomial-eval` | 1.0.0 | Numerical | sequential, multithreaded, distributed-compatible (`CHUNK`) | 512 exact (BigDecimal) samples within Horner's bound |
| `numeric.root-find` | 1.0.0 | Numerical | sequential, multithreaded, distributed-compatible (`RANGE`) | Sign-change bracket of every reported root |
| `numeric.monte-carlo` | 1.0.0 | Numerical | sequential, multithreaded, distributed-compatible (`RANGE`) | Statistical (5 standard errors) |
| `data.statistics` | 1.0.0 | Data processing | sequential, multithreaded, distributed-compatible (`CHUNK`) | Independent two-pass recomputation |
| `data.filter` | 1.0.0 | Data processing | sequential, multithreaded, distributed-compatible (`CHUNK`) | Exact sequential rescan |

"Distributed-compatible" means Core provides the partition plan, payload codecs, partial-result validation and
aggregation. Actual distributed execution needs Contributor 2's runtime. GPU is not implemented for any
algorithm.

### Partitioning per algorithm

| Algorithm | Partition | Partial result | Aggregation | Default plan |
|---|---|---|---|---|
| `primes.count` | sub-range of integers | count, with the range echoed back | sum (overflow-checked) | ≤ 4 × threads parts, each at least 2^20 integers |
| `sort.merge` | contiguous chunk | sorted run, checked for sortedness on arrival | k-way heap merge, O(n log k), sequential | ≤ 4 × threads chunks, each at least 2^14 elements |
| `matrix.add` | 2-D block (default: row bands) | result block | copy into C | 4 × threads row bands |
| `matrix.multiply` | 2-D result block (default: row bands) | result block | copy into C | 4 × threads row bands; `blockRows`/`blockCols` for 2-D |

Partition options come from `constraints.partitioning`: `targetPartitions` or `partitionSize` for ranges and
chunks, and `blockRows` with `blockCols` (or `targetPartitions` row bands) for matrices. Plans are deterministic.
`Partitioners.requireExactCover` checks that they cover the input exactly once.

## Deterministic input generation

Generated inputs depend only on the parameters, so every node, thread and repetition sees identical data.
Element `e` of the input on stream `s` is computed by `CounterRandom.longAt(seed, s, e)`. This is SplitMix64
mixing applied to `seed + (e + 1)·0x9E3779B97F4A7C15 + s·0xD1B54A32D192ED03` (mod 2^64). With stream 0 it
reproduces the published SplitMix64 reference sequence, and a unit test checks that. The formula is fully
specified in the `CounterRandom` Javadoc, so other repositories or languages can reproduce the inputs.

---

## `primes.count` — prime counting

Counts the primes in the inclusive range `[lowerBound, upperBound]`.

| Parameter | Type | Default | Constraints |
|---|---|---|---|
| `lowerBound` | integer | 0 | 0 ≤ value ≤ 10^11, ≤ `upperBound` |
| `upperBound` | integer | 10,000,000 | 0 ≤ value ≤ 10^11 |

* **Algorithm:** odd-only segmented sieve of Eratosthenes. It first sieves the base primes up to √upper. Each
  segment holds 2^18 odd candidates (256 KiB). The next multiple of every base prime is carried from one segment
  to the next, so no division per prime per segment is needed. Work: about (range/2)·Σ1/p composite marks.
  Memory: O(√upper) plus one segment.
* **Verification (independent algorithm):** the Lucy–Hedgehog prime-counting function π(x), which runs in
  O(x^3/4) time and shares no code path with the sieve. The check is count = π(upper) − π(lower − 1), compared
  exactly.
* **Tests:** every range within [0, 300] against trial division; the published values π(10^k) for k ≤ 8 (sieve)
  and k ≤ 11 (π function); 200 random ranges where sieve and π(x) must agree; ranges spanning many segments; a
  wrong count must be rejected.
* **Limitations:** upperBound ≤ 10^11. Cancellation and progress granularity is one segment (2^19 integers).

## `sort.merge` — merge sort

Sorts 64-bit signed integers in ascending order.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `input` | enum | `GENERATED` | `GENERATED` or `INLINE` |
| `size` | integer | 1,000,000 | 0 – 10^8, generated input only |
| `distribution` | enum | `UNIFORM` | `UNIFORM`, `SORTED`, `REVERSED`, `NEARLY_SORTED` (1% random swaps), `FEW_UNIQUE` (16 values) |
| `seed` | integer | 42 | generated input only |
| `values` | integer array | — | required for `INLINE`, at most 10^6 elements; rejected for `GENERATED` |

* **Algorithm:** top-down merge sort with ping-pong buffers (one copy instead of one per level), insertion sort
  for runs of 32 or fewer elements, and the merge skipped when two runs are already in order. The input is never
  modified.
* **Verification:** the output must be non-decreasing, and must be a permutation of the input: same element count
  and the same two order-independent 64-bit hash sums (`MultisetFingerprint`). A non-permutation passes only if
  both hash sums collide.
* **Tests:** every size from 0 to 200 against `Arrays.sort`; 250,000 elements including `Long.MIN_VALUE` and
  `Long.MAX_VALUE`; every distribution; rejection of unsorted, non-permutation and truncated outputs; inline-input
  validation.
* **Limitations:** 64-bit integers only. About 24 bytes of heap per element (input, output and merge buffer).

## `matrix.add` — matrix addition

Computes `C = A + B` for two dense `rows × cols` matrices of doubles.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `rows`, `cols` | integer | 1024 | 1 – 100,000; `rows·cols` must fit in one Java array |
| `input` | enum | `GENERATED` | `GENERATED` or `INLINE` |
| `distribution` | enum | `UNIFORM` | `UNIFORM` in [−1, 1), or `INTEGER` in [−8, 8] |
| `seed` | integer | 42 | stream 1 for A, stream 2 for B |
| `matrixA`, `matrixB` | decimal arrays | — | row-major, `INLINE` only, length must equal `rows·cols` |

* **Verification (exact recomputation):** every element is compared bit for bit with `A[i] + B[i]`.
* **Limitations:** memory-bound (24 bytes moved per addition), so core count does not set the speedup; memory
  bandwidth does.

## `matrix.multiply` — matrix multiplication (main demonstration)

Computes `C = A × B`, where A is `rowsA × colsA`, B is `rowsB × colsB` and `rowsB = colsA`.

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `rowsA`, `colsA`, `colsB` | integer | 512 | 1 – 20,000 |
| `rowsB` | integer | — | required for `INLINE`, must equal `colsA` (`DIMENSION_MISMATCH` otherwise) |
| `blockSize` | integer | 64 | 8 – 4096, cache tile edge |
| `input`, `distribution`, `seed`, `matrixA`, `matrixB` | | | as for `matrix.add`; `matrixA` length must be `rowsA·colsA` and `matrixB` length `rowsB·colsB` |

* **Algorithm:** a cache-blocked classical product. The loop order is `i0, p0, j0, i, p, j` with square tiles of
  edge `blockSize`. The strided kernel (`MatrixKernels.multiplyAdd`) works on whole matrices, views or compact
  sub-matrices, so every execution mode runs the same arithmetic.
* **Bitwise reproducibility:** each `C[i][j]` accumulates `A[i][p]·B[p][j]` in ascending `p`, starting from 0.0,
  whatever the tile size or decomposition. Java floating point is strict IEEE-754 and the kernel uses no fused
  multiply-add, so the result is bitwise identical to the naive triple loop. Tests assert this for 7 shapes and
  5 block sizes. Results produced in different modes therefore compare exactly by digest.
* **Verification:** Freivalds' algorithm with 3 random ±1 vectors, in O(mk + kn + mn). It accepts if
  `|C·r − A·(B·r)|` is within twice the rigorous rounding bound
  `γ_n·|C|·1 + (2γ_k + γ_n + γ_kγ_n)·|A|·(|B|·1)` per row, where `γ_j = j·u/(1 − j·u)` and `u = 2^−53`.
  * A correct product always passes.
  * A single wrong element whose error exceeds three times its row's bound is always detected.
  * Several errors that cancel in a row escape with probability at most 2^−3.
  * Errors below the rounding bound cannot be distinguished from rounding.
* **Tests:** bitwise equality with the naive product; exact products for `INTEGER` inputs; dimension-mismatch
  rejection; a deliberately perturbed element (+10^−6) is detected and its row reported; inline 2×3 · 3×2
  example; profile estimates.
* **Limitations:** dense doubles only. The classical O(mkn) algorithm (no Strassen). Three 20,000² matrices need
  about 9.6 GB, and the memory guard rejects such requests before allocating when the heap is too small.

---

## Numerical operations

The functions are an explicit catalog (`RealFunction`): `POLYNOMIAL` (coefficients `a0, a1, …`, degree < 64),
`SIN`, `COS` and `EXP`. Arbitrary expressions are deliberately not supported. Every catalog function has a
closed-form antiderivative and computable bounds for |f| and |f''''|, which give analytic reference values and
rigorous tolerances.

| Operation | Method | Partitioning | Cross-mode equality | Verification and tolerance | Limitations |
|---|---|---|---|---|---|
| `numeric.integrate` | Composite Simpson on `panels` equal panels, Neumaier-compensated sums | Ranges of panel indices; partial compensated sums are combined | Within rounding (floating-point addition is not associative) | `F(b) − F(a)` within 2 × (Simpson truncation `(b−a)h⁴/2880·max|f''''|` + rounding bound) | Exact only up to cubics; 3 evaluations per panel |
| `numeric.polynomial-eval` | Horner at a uniform grid or inline points | Chunks of points | **Bitwise** | 512 deterministic samples evaluated exactly with `BigDecimal`, within `2·γ_2d·Σ|a_i||x|^i` | Unsampled points are covered by cross-mode equality, not exact checks |
| `numeric.root-find` | Scan `subintervals` for sign changes, then bisect to `tolerance` | Ranges of subintervals (root isolation); bisection of one root stays sequential | **Bitwise** | Each root lies in range, roots increase, and a sign change occurs within ±tolerance (or the value is zero within rounding) | Misses roots without a sign change (even multiplicity) and roots closer than one subinterval; cannot prove completeness |
| `numeric.monte-carlo` | π from `samples` points; sample i uses `CounterRandom(seed, 1/2, i)` | Ranges of sample indices | **Bitwise** (the hit count doesn't depend on partitioning) | Statistical: within 5 standard errors of π (false alarm ≈ 6·10⁻⁷) | Accuracy O(1/√n); statistical verification |

## Data-processing operations

| Operation | Method | Partitioning | Cross-mode equality | Verification | Limitations |
|---|---|---|---|---|---|
| `data.statistics` | One pass per chunk (Welford); chunks merged with Chan–Golub–LeVeque | Chunks | Count, min and max exact; mean and variance within rounding | Independent two-pass recomputation; relative tolerance 1e-9 of the data scale (generous, not a proven bound) | At least one value; `NORMAL` data via Box–Muller |
| `data.filter` | Count values in `[minValue, maxValue]` and return the first `limit` indices | Chunks; counts summed, indices concatenated in chunk order and truncated | **Bitwise** | Exact sequential rescan | 64-bit integers; inclusive range predicate; at most 10,000 indices returned |

Sorting is `sort.merge` (above). Searching and filtering are both served by `data.filter`.

**Iterative algorithms.** Operations whose steps depend on each other (bisection, Newton iteration, time-stepping
simulations) cannot be freely partitioned. Core parallelizes only their independent parts (for example root
isolation), and their profiles report `dataDependency = ITERATIVE`.
