package org.districompute.app.task;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.districompute.app.api.ApiException;
import org.districompute.app.api.ApiException.FieldViolation;
import org.districompute.app.catalog.ApplicationCategory;
import org.districompute.app.catalog.CatalogService;
import org.districompute.app.config.AppProperties;
import org.districompute.app.persistence.AppUser;
import org.districompute.app.persistence.Enums.InputSource;
import org.districompute.app.task.InputStore.InputMeta;
import org.districompute.core.DistriComputeException;
import org.districompute.core.algorithm.ComputeAlgorithm;
import org.districompute.core.algorithm.ParameterSpec;
import org.districompute.core.algorithm.ParameterType;
import org.districompute.core.algorithm.Parameters;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.execution.TaskPreparation;
import org.districompute.core.partition.PartitioningOptions;
import org.districompute.core.registry.AlgorithmInfo;
import org.districompute.core.registry.AlgorithmRegistry;
import org.districompute.core.resource.WorkloadProfile;
import org.districompute.core.task.TaskPriority;
import org.districompute.core.task.TaskSpec;
import org.districompute.core.validation.ValidationException;
import org.springframework.stereotype.Service;

/**
 * Turns a {@link TaskDraft} into Core {@link TaskSpec}s: application limits first, then Core's own validation
 * ({@code TaskPreparation.validate}) and workload analysis ({@code analyze}). Nothing is computed here.
 */
@Service
public class TaskFactory {

    /** Largest array accepted inline in a JSON request; larger inputs must be uploaded. */
    public static final int MAX_INLINE_ELEMENTS = 10_000;

    private final CatalogService catalog;
    private final AlgorithmRegistry registry;
    private final InputStore inputs;
    private final AppProperties.Limits limits;
    private final Clock clock;

    TaskFactory(CatalogService catalog, AlgorithmRegistry registry, InputStore inputs, AppProperties properties,
            Clock clock) {
        this.catalog = catalog;
        this.registry = registry;
        this.inputs = inputs;
        this.limits = properties.limits();
        this.clock = clock;
    }

    /**
     * A draft that passed every check.
     *
     * @param info Core's algorithm information
     * @param category application category
     * @param mode execution mode
     * @param parameters Core parameters including uploaded arrays (for building specs)
     * @param storedParameters parameters as stored with the experiment (uploaded arrays replaced by references)
     * @param uploads uploaded inputs by parameter
     * @param inputSource where the input comes from
     * @param settings execution settings, with settings that do not apply to the mode removed
     * @param profile Core's workload analysis
     */
    public record PreparedTask(AlgorithmInfo info, ApplicationCategory category, ExecutionMode mode,
            Map<String, Object> parameters, Map<String, Object> storedParameters, Map<String, InputMeta> uploads,
            InputSource inputSource, ExecutionSettings settings, WorkloadProfile profile) {

        /**
         * The algorithm version Core resolved.
         *
         * @return the version
         */
        public String algorithmVersion() {
            return info.descriptor().version().toString();
        }
    }

    /**
     * Validates a draft.
     *
     * @param draft the draft
     * @param owner the submitting user (for upload ownership)
     * @param admin whether the user is an administrator
     * @return the prepared task
     * @throws ApiException 400 with every violation found
     */
    public PreparedTask prepare(TaskDraft draft, AppUser owner, boolean admin) {
        List<FieldViolation> violations = new ArrayList<>();
        AlgorithmInfo info = catalog.find(draft.algorithm()).orElseThrow(() -> ApiException.invalid(
                "Unknown algorithm", List.of(new FieldViolation("algorithm", "UNKNOWN_ALGORITHM",
                        "No algorithm '" + draft.algorithm() + "' is registered in DistriCompute-Core"))));
        ExecutionMode mode = ExecutionMode.valueOf(draft.execution().mode());
        checkMode(info, mode, violations);

        Map<String, Object> parameters = ParameterValues.normalize(draft.parameters());
        Map<String, Object> stored = new LinkedHashMap<>(parameters);
        Map<String, InputMeta> uploads = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : parameters.entrySet()) {
            if (e.getValue() instanceof List<?> list && list.size() > MAX_INLINE_ELEMENTS) {
                violations.add(new FieldViolation("parameters." + e.getKey(), "TOO_LONG_INLINE", list.size()
                        + " values inline; upload inputs with more than " + MAX_INLINE_ELEMENTS + " values"));
            }
        }
        for (Map.Entry<String, String> e : draft.inputs().entrySet()) {
            Optional<ParameterSpec> spec = info.descriptor().parameters().stream()
                    .filter(p -> p.name().equals(e.getKey())).findFirst();
            if (spec.isEmpty() || !isArray(spec.get())) {
                violations.add(new FieldViolation("inputs." + e.getKey(), "NOT_AN_INPUT",
                        e.getKey() + " is not an array parameter of " + draft.algorithm()));
                continue;
            }
            InputMeta meta = inputs.meta(e.getValue(), owner, admin);
            boolean integral = spec.get().type() == ParameterType.INTEGER_ARRAY;
            if (integral != "INTEGER".equals(meta.elementType()) || !meta.algorithm().equals(draft.algorithm())) {
                violations.add(new FieldViolation("inputs." + e.getKey(), "WRONG_INPUT",
                        "Upload " + meta.id() + " was made for " + meta.algorithm() + "." + meta.parameter()));
                continue;
            }
            try {
                parameters.put(e.getKey(), inputs.values(meta));
            } catch (IOException io) {
                throw new UncheckedIOException(io);
            }
            stored.put(e.getKey(), Map.of("upload", meta.id(), "values", meta.count()));
            uploads.put(e.getKey(), meta);
        }
        InputSource source = !uploads.isEmpty() ? InputSource.UPLOAD
                : ParameterValues.largestArray(parameters) > 0 && hasInlineChoice(info) ? InputSource.INLINE
                : InputSource.GENERATED;
        if (source != InputSource.GENERATED && hasInlineChoice(info) && !parameters.containsKey("input")) {
            parameters.put("input", "INLINE");
            stored.put("input", "INLINE");
        }
        ExecutionSettings settings = applicable(draft.execution(), mode, violations);
        if (!violations.isEmpty()) {
            throw ApiException.invalid("The task is invalid", violations);
        }

        TaskSpec probe = spec(info, mode, parameters, settings, "validation-probe", null);
        ComputeAlgorithm<?, ?> algorithm = registry.resolve(probe.algorithm());
        Parameters validated;
        try {
            validated = TaskPreparation.validate(algorithm, probe, mode != ExecutionMode.SEQUENTIAL_CPU);
        } catch (ValidationException e) {
            throw ApiException.invalid("Core rejected the parameters", e.violations().stream()
                    .map(v -> new FieldViolation("parameters." + v.field(), v.code(), v.message())).toList());
        } catch (DistriComputeException e) {
            throw ApiException.invalid(e.getMessage(), List.of(new FieldViolation("execution.mode", e.code().name(),
                    e.getMessage())));
        }
        WorkloadProfile profile = algorithm.analyze(validated);
        checkLimits(profile);
        return new PreparedTask(info, catalog.categoryOf(info), mode, parameters, stored, uploads, source, settings,
                profile);
    }

    /**
     * Builds the Core task of one run.
     *
     * @param task the prepared task
     * @param taskId Core task id
     * @param experimentId Core experiment id
     * @return the specification
     */
    public TaskSpec spec(PreparedTask task, String taskId, String experimentId) {
        return spec(task.info(), task.mode(), task.parameters(), task.settings(), taskId, experimentId);
    }

    private TaskSpec spec(AlgorithmInfo info, ExecutionMode mode, Map<String, Object> parameters,
            ExecutionSettings s, String taskId, String experimentId) {
        TaskSpec.Builder builder = TaskSpec.builder(info.descriptor().id().value())
                .version(info.descriptor().version().toString()).taskId(taskId).parameters(parameters).mode(mode)
                .createdAt(clock.instant());
        if (experimentId != null) {
            builder.experimentId(experimentId);
        }
        if (mode == ExecutionMode.MULTITHREADED_CPU) {
            builder.maxThreads(s.threads() != null ? s.threads() : maxThreads());
        }
        if (s.targetPartitions() != null || s.partitionSize() != null || s.blockRows() != null
                || s.blockCols() != null) {
            builder.partitioning(new PartitioningOptions(s.targetPartitions(), s.partitionSize(), s.blockRows(),
                    s.blockCols()));
        }
        Duration deadline = s.timeoutSeconds() != null ? Duration.ofSeconds(s.timeoutSeconds())
                : limits.maxRunDuration();
        builder.timeoutMillis(deadline.toMillis());
        if (s.maxMemoryMb() != null) {
            builder.maxMemoryBytes(s.maxMemoryMb() * 1024 * 1024);
        }
        if (s.priority() != null) {
            builder.priority(TaskPriority.valueOf(s.priority()));
        }
        return builder.build();
    }

    private void checkMode(AlgorithmInfo info, ExecutionMode mode, List<FieldViolation> violations) {
        if (mode == ExecutionMode.GPU) {
            violations.add(new FieldViolation("execution.mode", "MODE_UNAVAILABLE",
                    "GPU execution is not available: " + registry.gpuStatus().summary()));
        } else if (mode == ExecutionMode.HYBRID) {
            violations.add(new FieldViolation("execution.mode", "MODE_UNAVAILABLE",
                    "Hybrid execution is not implemented by the components"));
        } else if (!info.capabilities().supportedModes().contains(mode)) {
            violations.add(new FieldViolation("execution.mode", "MODE_UNSUPPORTED",
                    "Core does not support " + mode + " for " + info.descriptor().id()));
        }
    }

    private ExecutionSettings applicable(ExecutionSettings s, ExecutionMode mode, List<FieldViolation> violations) {
        if (s.repetitionCount() > limits.maxRepetitions()) {
            violations.add(new FieldViolation("execution.repetitions", "TOO_MANY", "At most "
                    + limits.maxRepetitions() + " repetitions are allowed"));
        }
        if (s.threads() != null && mode == ExecutionMode.MULTITHREADED_CPU && s.threads() > maxThreads()) {
            violations.add(new FieldViolation("execution.threads", "TOO_MANY", "At most " + maxThreads()
                    + " threads are allowed on this server"));
        }
        if (s.timeoutSeconds() != null && Duration.ofSeconds(s.timeoutSeconds()).compareTo(limits.maxRunDuration())
                > 0) {
            violations.add(new FieldViolation("execution.timeoutSeconds", "TOO_LONG", "Runs may take at most "
                    + limits.maxRunDuration().toSeconds() + " s"));
        }
        boolean distributed = mode == ExecutionMode.DISTRIBUTED_CPU;
        boolean partitioned = mode != ExecutionMode.SEQUENTIAL_CPU;
        return new ExecutionSettings(mode.name(), mode == ExecutionMode.MULTITHREADED_CPU ? s.threads() : null,
                partitioned ? s.targetPartitions() : null, partitioned ? s.partitionSize() : null,
                partitioned ? s.blockRows() : null, partitioned ? s.blockCols() : null,
                distributed ? s.partitionsPerSlot() : null, distributed ? s.schedulingPolicy() : null,
                distributed ? s.maxWorkers() : null, distributed ? s.maxAttempts() : null,
                distributed ? s.attemptTimeoutSeconds() : null, s.timeoutSeconds(), s.maxMemoryMb(), s.priority(),
                s.repetitionCount());
    }

    private void checkLimits(WorkloadProfile profile) {
        List<FieldViolation> violations = new ArrayList<>();
        if (profile.estimatedOperations() > limits.maxEstimatedOperations()) {
            violations.add(new FieldViolation("parameters", "WORKLOAD_TOO_LARGE", String.format(
                    "Core estimates %.3g %s; this server accepts at most %.3g", profile.estimatedOperations(),
                    profile.operationUnit(), limits.maxEstimatedOperations())));
        }
        if (profile.estimatedPeakMemoryBytes() > limits.maxEstimatedMemoryBytes()) {
            violations.add(new FieldViolation("parameters", "MEMORY_TOO_LARGE", "Core estimates "
                    + mib(profile.estimatedPeakMemoryBytes()) + " MiB of peak memory; this server accepts at most "
                    + mib(limits.maxEstimatedMemoryBytes()) + " MiB"));
        }
        if (!violations.isEmpty()) {
            throw ApiException.invalid("The task exceeds this server's limits", violations);
        }
    }

    private int maxThreads() {
        return limits.maxThreads() > 0 ? limits.maxThreads() : Runtime.getRuntime().availableProcessors();
    }

    private static long mib(long bytes) {
        return (bytes + (1 << 20) - 1) >> 20;
    }

    private static boolean isArray(ParameterSpec spec) {
        return spec.type() == ParameterType.INTEGER_ARRAY || spec.type() == ParameterType.DECIMAL_ARRAY;
    }

    private static boolean hasInlineChoice(AlgorithmInfo info) {
        return info.descriptor().parameters().stream().anyMatch(p -> "input".equals(p.name())
                && p.allowedValues() != null && p.allowedValues().contains("INLINE"));
    }
}
