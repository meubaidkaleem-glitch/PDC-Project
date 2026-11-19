package org.districompute.core.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.districompute.core.DistriComputeException;
import org.districompute.core.ErrorCode;
import org.districompute.core.algorithm.AlgorithmCategory;
import org.districompute.core.algorithm.AlgorithmDescriptor;
import org.districompute.core.algorithm.AlgorithmId;
import org.districompute.core.algorithm.AlgorithmRef;
import org.districompute.core.algorithm.AlgorithmVersion;
import org.districompute.core.algorithm.ComputeAlgorithm;
import org.districompute.core.algorithm.ParameterSpec;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.fixtures.SumOfSquaresAlgorithm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AlgorithmRegistryTest {

    @Test
    void resolvesRegisteredAlgorithmsWithCompatibleVersions() {
        SumOfSquaresAlgorithm algorithm = new SumOfSquaresAlgorithm();
        AlgorithmRegistry registry = AlgorithmRegistry.of(algorithm);
        assertSame(algorithm, registry.resolve(AlgorithmRef.of(SumOfSquaresAlgorithm.ID)));
        assertSame(algorithm, registry.resolve(AlgorithmRef.of(SumOfSquaresAlgorithm.ID, "1.1.0")));

        DistriComputeException newer = assertThrows(DistriComputeException.class,
                () -> registry.resolve(AlgorithmRef.of(SumOfSquaresAlgorithm.ID, "1.3.0")));
        assertEquals(ErrorCode.UNSUPPORTED_ALGORITHM_VERSION, newer.code());
        DistriComputeException unknown = assertThrows(DistriComputeException.class,
                () -> registry.resolve(AlgorithmRef.of("matrix.unknown")));
        assertEquals(ErrorCode.UNKNOWN_ALGORITHM, unknown.code());
    }

    @Test
    void catalogIsSortedAndCapabilitiesAreDerived() {
        AlgorithmRegistry registry = AlgorithmRegistry.of(new SumOfSquaresAlgorithm("zeta.sum", "1.0.0"),
                new SumOfSquaresAlgorithm("alpha.sum", "1.0.0"));
        List<AlgorithmInfo> catalog = registry.catalog();
        assertEquals(List.of("alpha.sum", "zeta.sum"),
                catalog.stream().map(info -> info.descriptor().id().value()).toList());
        AlgorithmCapabilities capabilities = catalog.get(0).capabilities();
        assertEquals(List.of(ExecutionMode.SEQUENTIAL_CPU), capabilities.supportedModes());
        assertTrue(capabilities.supports(ExecutionMode.SEQUENTIAL_CPU));
    }

    @Test
    void rejectsDuplicateIds() {
        assertThrows(IllegalStateException.class,
                () -> AlgorithmRegistry.of(new SumOfSquaresAlgorithm(), new SumOfSquaresAlgorithm()));
    }

    @Test
    void rejectsDefaultsThatViolateTheirOwnSchema() {
        SumOfSquaresAlgorithm broken = new SumOfSquaresAlgorithm() {
            @Override
            public AlgorithmDescriptor descriptor() {
                return new AlgorithmDescriptor(AlgorithmId.of("test.broken"), AlgorithmVersion.parse("1.0.0"),
                        "Broken", "Default outside its range", AlgorithmCategory.NUMERICAL,
                        List.of(ParameterSpec.integer("n", "n").range(1, 10).defaultValue(11).build()),
                        "count", "none", List.of());
            }
        };
        assertThrows(IllegalStateException.class, () -> AlgorithmRegistry.of(broken));
    }

    @Test
    void capabilitiesCannotClaimModesInconsistently() {
        assertThrows(IllegalArgumentException.class,
                () -> new AlgorithmCapabilities(List.of(ExecutionMode.MULTITHREADED_CPU), null, false, false));
        assertThrows(IllegalArgumentException.class, () -> new AlgorithmCapabilities(
                List.of(ExecutionMode.SEQUENTIAL_CPU, ExecutionMode.DISTRIBUTED_CPU), null, false, false));
        assertThrows(IllegalArgumentException.class, () -> new AlgorithmCapabilities(
                List.of(ExecutionMode.SEQUENTIAL_CPU), null, false, true));
    }

    @Test
    void discoversProvidersWithServiceLoader(@TempDir Path dir) throws Exception {
        Path services = dir.resolve("META-INF/services");
        Files.createDirectories(services);
        Files.writeString(services.resolve(AlgorithmProvider.class.getName()), TestProvider.class.getName() + "\n",
                StandardCharsets.UTF_8);
        try (URLClassLoader loader = new URLClassLoader(new URL[] {dir.toUri().toURL()},
                getClass().getClassLoader())) {
            AlgorithmRegistry registry = AlgorithmRegistry.load(loader);
            assertTrue(registry.find(AlgorithmId.of("provided.sum")).isPresent());
        }
    }

    /** Provider used by the service-loading test. */
    public static final class TestProvider implements AlgorithmProvider {
        @Override
        public List<ComputeAlgorithm<?, ?>> algorithms() {
            return List.of(new SumOfSquaresAlgorithm("provided.sum", "1.0.0"));
        }
    }
}
