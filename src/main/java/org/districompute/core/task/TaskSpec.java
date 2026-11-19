package org.districompute.core.task;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.districompute.core.algorithm.AlgorithmId;
import org.districompute.core.algorithm.AlgorithmRef;
import org.districompute.core.algorithm.AlgorithmVersion;
import org.districompute.core.algorithm.ParameterValues;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.partition.PartitioningOptions;
import org.districompute.core.serialization.SchemaVersion;

/**
 * Immutable request to run one algorithm on one input: the universal task representation (integration contract,
 * section 2).
 *
 * <p>The input is described by {@code parameters}. Generated inputs use dimensions, a {@code seed} and a
 * distribution. Small inline inputs use array parameters. Parameter values are normalized to a JSON-compatible model
 * (see {@link ParameterValues}) and validated later against the algorithm's parameter schema.
 *
 * @param schemaVersion contract schema version, see {@link SchemaVersion}
 * @param taskId unique task identifier: 1–128 characters from {@code [A-Za-z0-9._:-]}
 * @param experimentId optional experiment identifier grouping repeated runs, same character rules
 * @param algorithm algorithm and optional version requirement
 * @param parameters algorithm-specific parameters (normalized, unmodifiable)
 * @param constraints requested execution constraints
 * @param priority scheduling hint
 * @param createdAt creation timestamp
 */
public record TaskSpec(
        String schemaVersion,
        String taskId,
        String experimentId,
        AlgorithmRef algorithm,
        Map<String, Object> parameters,
        ExecutionConstraints constraints,
        TaskPriority priority,
        Instant createdAt) {

    private static final Pattern ID_FORMAT = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    /** Validates the components, applies defaults and normalizes the parameters. */
    public TaskSpec {
        SchemaVersion.requireCompatible(schemaVersion);
        requireIdFormat("taskId", Objects.requireNonNull(taskId, "taskId"));
        if (experimentId != null) {
            requireIdFormat("experimentId", experimentId);
        }
        Objects.requireNonNull(algorithm, "algorithm");
        parameters = ParameterValues.normalizeAll(parameters);
        Objects.requireNonNull(constraints, "constraints");
        priority = priority == null ? TaskPriority.NORMAL : priority;
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /**
     * Starts building a task for an algorithm, with a random UUID task id and the current time.
     *
     * @param algorithmId algorithm identifier, for example {@code "matrix.multiply"}
     * @return a builder
     */
    public static Builder builder(String algorithmId) {
        return new Builder(AlgorithmId.of(algorithmId));
    }

    private static void requireIdFormat(String field, String value) {
        if (!ID_FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " '" + value
                    + "' must be 1-128 characters from [A-Za-z0-9._:-]");
        }
    }

    /** Fluent builder for {@link TaskSpec}. */
    public static final class Builder {
        private final AlgorithmId algorithmId;
        private final Map<String, Object> parameters = new LinkedHashMap<>();
        private AlgorithmVersion version;
        private String taskId = UUID.randomUUID().toString();
        private String experimentId;
        private ExecutionMode mode = ExecutionMode.SEQUENTIAL_CPU;
        private Integer maxThreads;
        private PartitioningOptions partitioning;
        private Long timeoutMillis;
        private Long maxMemoryBytes;
        private TaskPriority priority = TaskPriority.NORMAL;
        private Instant createdAt;

        private Builder(AlgorithmId algorithmId) {
            this.algorithmId = algorithmId;
        }

        /**
         * Requires a compatible algorithm version.
         *
         * @param semanticVersion version such as {@code "1.0.0"}
         * @return this builder
         */
        public Builder version(String semanticVersion) {
            this.version = AlgorithmVersion.parse(semanticVersion);
            return this;
        }

        /**
         * Sets the task id (default: a random UUID).
         *
         * @param id task id
         * @return this builder
         */
        public Builder taskId(String id) {
            this.taskId = id;
            return this;
        }

        /**
         * Sets the experiment id.
         *
         * @param id experiment id
         * @return this builder
         */
        public Builder experimentId(String id) {
            this.experimentId = id;
            return this;
        }

        /**
         * Adds a parameter.
         *
         * @param name parameter name
         * @param value parameter value
         * @return this builder
         */
        public Builder parameter(String name, Object value) {
            parameters.put(name, value);
            return this;
        }

        /**
         * Adds several parameters.
         *
         * @param values parameters by name
         * @return this builder
         */
        public Builder parameters(Map<String, ?> values) {
            parameters.putAll(values);
            return this;
        }

        /**
         * Sets the execution mode (default {@link ExecutionMode#SEQUENTIAL_CPU}).
         *
         * @param executionMode mode
         * @return this builder
         */
        public Builder mode(ExecutionMode executionMode) {
            this.mode = executionMode;
            return this;
        }

        /**
         * Sets the thread count for multithreaded execution.
         *
         * @param threads thread count
         * @return this builder
         */
        public Builder maxThreads(int threads) {
            this.maxThreads = threads;
            return this;
        }

        /**
         * Sets the partitioning request.
         *
         * @param options partitioning options
         * @return this builder
         */
        public Builder partitioning(PartitioningOptions options) {
            this.partitioning = options;
            return this;
        }

        /**
         * Sets the execution deadline.
         *
         * @param millis timeout in milliseconds
         * @return this builder
         */
        public Builder timeoutMillis(long millis) {
            this.timeoutMillis = millis;
            return this;
        }

        /**
         * Caps the memory the task may use.
         *
         * @param bytes memory cap in bytes
         * @return this builder
         */
        public Builder maxMemoryBytes(long bytes) {
            this.maxMemoryBytes = bytes;
            return this;
        }

        /**
         * Sets the priority (default {@link TaskPriority#NORMAL}).
         *
         * @param taskPriority priority
         * @return this builder
         */
        public Builder priority(TaskPriority taskPriority) {
            this.priority = taskPriority;
            return this;
        }

        /**
         * Sets the creation timestamp (default: now).
         *
         * @param instant creation time
         * @return this builder
         */
        public Builder createdAt(Instant instant) {
            this.createdAt = instant;
            return this;
        }

        /**
         * Builds the task specification.
         *
         * @return the validated task specification
         */
        public TaskSpec build() {
            ExecutionConstraints constraints =
                    new ExecutionConstraints(mode, maxThreads, partitioning, timeoutMillis, maxMemoryBytes);
            return new TaskSpec(SchemaVersion.CURRENT, taskId, experimentId, new AlgorithmRef(algorithmId, version),
                    parameters, constraints, priority, createdAt == null ? Instant.now() : createdAt);
        }
    }
}
