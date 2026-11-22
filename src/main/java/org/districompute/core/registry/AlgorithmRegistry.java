package org.districompute.core.registry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import org.districompute.core.DistriComputeException;
import org.districompute.core.ErrorCode;
import org.districompute.core.algorithm.AlgorithmDescriptor;
import org.districompute.core.algorithm.AlgorithmId;
import org.districompute.core.algorithm.AlgorithmRef;
import org.districompute.core.algorithm.ComputeAlgorithm;
import org.districompute.core.algorithm.ParameterSpec;
import org.districompute.core.algorithm.PartitionedAlgorithm;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.validation.ParameterValidator;
import org.districompute.core.validation.ValidationException;

/**
 * Immutable catalog of the algorithms available to this process, used for discovery by the application and the
 * distributed runtime.
 *
 * <p>Registration fails fast on programming errors: duplicate identifiers, or defaults that violate their own
 * parameter schema. Capabilities are derived from the implemented interfaces (see
 * {@link AlgorithmCapabilities}).
 */
public final class AlgorithmRegistry {

    private final Map<AlgorithmId, ComputeAlgorithm<?, ?>> algorithms;
    private final Map<AlgorithmId, AlgorithmInfo> catalog;

    private AlgorithmRegistry(Collection<? extends ComputeAlgorithm<?, ?>> candidates) {
        Map<AlgorithmId, ComputeAlgorithm<?, ?>> byId = new TreeMap<>();
        Map<AlgorithmId, AlgorithmInfo> infos = new TreeMap<>();
        for (ComputeAlgorithm<?, ?> algorithm : candidates) {
            AlgorithmDescriptor descriptor = Objects.requireNonNull(algorithm.descriptor(),
                    () -> algorithm.getClass().getName() + " returned a null descriptor");
            if (byId.putIfAbsent(descriptor.id(), algorithm) != null) {
                throw new IllegalStateException("Duplicate algorithm id " + descriptor.id() + ": "
                        + byId.get(descriptor.id()).getClass().getName() + " and " + algorithm.getClass().getName());
            }
            checkDefaults(descriptor);
            infos.put(descriptor.id(), new AlgorithmInfo(descriptor, deriveCapabilities(algorithm)));
        }
        this.algorithms = byId;
        this.catalog = infos;
    }

    /**
     * Creates a registry from the given algorithms.
     *
     * @param algorithms algorithm instances
     * @return the registry
     */
    public static AlgorithmRegistry of(Collection<? extends ComputeAlgorithm<?, ?>> algorithms) {
        return new AlgorithmRegistry(List.copyOf(algorithms));
    }

    /**
     * Creates a registry from the given algorithms.
     *
     * @param algorithms algorithm instances
     * @return the registry
     */
    public static AlgorithmRegistry of(ComputeAlgorithm<?, ?>... algorithms) {
        return of(Arrays.asList(algorithms));
    }

    /**
     * Discovers algorithms with {@link ServiceLoader} through {@link AlgorithmProvider} implementations visible to
     * this library's class loader. Includes the built-in algorithms.
     *
     * @return the registry
     */
    public static AlgorithmRegistry loadDefault() {
        return load(AlgorithmRegistry.class.getClassLoader());
    }

    /**
     * Discovers algorithms with {@link ServiceLoader} using the given class loader.
     *
     * @param classLoader class loader to search
     * @return the registry
     */
    public static AlgorithmRegistry load(ClassLoader classLoader) {
        List<ComputeAlgorithm<?, ?>> found = new ArrayList<>();
        for (AlgorithmProvider provider : ServiceLoader.load(AlgorithmProvider.class, classLoader)) {
            found.addAll(provider.algorithms());
        }
        return of(found);
    }

    /**
     * Finds an algorithm by identifier.
     *
     * @param id algorithm identifier
     * @return the algorithm, if registered
     */
    public Optional<ComputeAlgorithm<?, ?>> find(AlgorithmId id) {
        return Optional.ofNullable(algorithms.get(id));
    }

    /**
     * Resolves an algorithm reference, checking version compatibility.
     *
     * @param ref algorithm reference; a missing version means "the registered version"
     * @return the algorithm
     * @throws DistriComputeException with {@link ErrorCode#UNKNOWN_ALGORITHM} or
     *     {@link ErrorCode#UNSUPPORTED_ALGORITHM_VERSION}
     */
    public ComputeAlgorithm<?, ?> resolve(AlgorithmRef ref) {
        ComputeAlgorithm<?, ?> algorithm = algorithms.get(ref.id());
        if (algorithm == null) {
            throw new DistriComputeException(ErrorCode.UNKNOWN_ALGORITHM,
                    "Unknown algorithm '" + ref.id() + "'; registered: " + algorithms.keySet());
        }
        AlgorithmDescriptor descriptor = algorithm.descriptor();
        if (ref.version() != null && !descriptor.version().satisfies(ref.version())) {
            throw new DistriComputeException(ErrorCode.UNSUPPORTED_ALGORITHM_VERSION,
                    "Requested " + ref + " but " + descriptor.id() + "@" + descriptor.version() + " is registered");
        }
        return algorithm;
    }

    /**
     * Returns the catalog, sorted by identifier.
     *
     * @return catalog entries
     */
    public List<AlgorithmInfo> catalog() {
        return List.copyOf(catalog.values());
    }

    /**
     * Returns the catalog entry of an algorithm.
     *
     * @param id algorithm identifier
     * @return the entry, if registered
     */
    public Optional<AlgorithmInfo> info(AlgorithmId id) {
        return Optional.ofNullable(catalog.get(id));
    }

    /**
     * Derives capabilities from the interfaces an algorithm implements.
     *
     * @param algorithm the algorithm
     * @return its capabilities
     */
    static AlgorithmCapabilities deriveCapabilities(ComputeAlgorithm<?, ?> algorithm) {
        Set<ExecutionMode> modes = EnumSet.of(ExecutionMode.SEQUENTIAL_CPU);
        if (algorithm instanceof PartitionedAlgorithm<?, ?, ?, ?> partitioned) {
            modes.add(ExecutionMode.MULTITHREADED_CPU);
            modes.add(ExecutionMode.DISTRIBUTED_CPU);
            return new AlgorithmCapabilities(List.copyOf(modes), partitioned.partitionStrategy(), true, false);
        }
        return new AlgorithmCapabilities(List.copyOf(modes), null, false, false);
    }

    private static void checkDefaults(AlgorithmDescriptor descriptor) {
        for (ParameterSpec spec : descriptor.parameters()) {
            if (spec.defaultValue() == null) {
                continue;
            }
            try {
                ParameterValidator.validate(List.of(spec), Map.of(spec.name(), spec.defaultValue()));
            } catch (ValidationException e) {
                throw new IllegalStateException("Algorithm " + descriptor.id() + " has an invalid default for '"
                        + spec.name() + "': " + e.getMessage(), e);
            }
        }
    }
}
