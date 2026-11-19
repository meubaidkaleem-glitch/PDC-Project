package org.districompute.core.validation;

/** The kind of evidence a result verification provides. */
public enum VerificationKind {
    /** The result was recomputed exactly, possibly by the same formula (for example matrix addition). */
    EXACT_RECOMPUTATION,
    /** An independent algorithm produced the same exact answer (for example a prime-counting function). */
    INDEPENDENT_ALGORITHM,
    /** A randomized check with a stated error probability (for example Freivalds' algorithm). */
    PROBABILISTIC,
    /** Comparison with a closed-form reference value within a stated tolerance. */
    ANALYTIC_REFERENCE,
    /** A statistical check, such as a confidence interval around a known value. */
    STATISTICAL,
    /** Structural invariants only (shape, ordering, coverage, checksums). */
    STRUCTURAL
}
