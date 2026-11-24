package org.districompute.app.persistence;

/** Enumerations stored in the database (as their names). */
public final class Enums {

    private Enums() {
    }

    /** Lifecycle of an experiment (all its runs). */
    public enum ExperimentStatus {
        QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED;

        /**
         * Whether the experiment has ended.
         *
         * @return {@code true} for completed, failed and cancelled experiments
         */
        public boolean isTerminal() {
            return this == COMPLETED || this == FAILED || this == CANCELLED;
        }
    }

    /** Lifecycle of one run (one Core task). */
    public enum RunStatus {
        PENDING, QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED;

        /**
         * Whether the run has ended.
         *
         * @return {@code true} for completed, failed and cancelled runs
         */
        public boolean isTerminal() {
            return this == COMPLETED || this == FAILED || this == CANCELLED;
        }
    }

    /** Where a task's input comes from. */
    public enum InputSource { GENERATED, INLINE, UPLOAD }

    /** Who measured or computed a metric. */
    public enum MetricSource {
        /** Measured by the application (wall time from submission to completion). */
        APPLICATION,
        /** Reported by DistriCompute-Core ({@code ExecutionMetrics}). */
        CORE,
        /** Reported by DistriCompute-Distributed ({@code DistributedMetrics}). */
        RUNTIME,
        /** Computed by the application from measured values. */
        DERIVED
    }

    /** Result validation outcome. */
    public enum ValidationStatus { VERIFIED, VERIFICATION_FAILED, NOT_VERIFIED }

    /** Format of a stored result file. */
    public enum StorageFormat { CSV, JSON }

    /** Severity of an execution event. */
    public enum Severity { INFO, WARN, ERROR }

    /** Application roles. */
    public enum Role { ADMIN, USER }

    /** How a recommendation was estimated. */
    public enum AdviceBasis {
        /** Core's cost model without measured history. */
        THEORETICAL,
        /** Core's cost model calibrated with measured runs. */
        HISTORICAL,
        /** No mode could be recommended. */
        NONE
    }
}
