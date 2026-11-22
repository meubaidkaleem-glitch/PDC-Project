package org.districompute.core.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.districompute.core.algorithm.AlgorithmId;
import org.districompute.core.algorithm.ComputeAlgorithm;
import org.districompute.core.execution.ExecutionContext;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.registry.AlgorithmCapabilities;
import org.districompute.core.registry.AlgorithmRegistry;
import org.districompute.core.registry.BuiltInAlgorithms;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GpuStatusTest {

    @Test
    void withoutABackendGpuIsUnavailableWithAReason() {
        GpuStatus status = GpuStatus.detect(getClass().getClassLoader());
        assertTrue(status.available().isEmpty());
        assertTrue(status.summary().contains("No GPU backend is installed"), status.summary());
        AlgorithmCapabilities capabilities = AlgorithmRegistry.loadDefault()
                .info(AlgorithmId.of("matrix.multiply")).orElseThrow().capabilities();
        assertFalse(capabilities.gpu());
        assertFalse(capabilities.supports(ExecutionMode.GPU));
    }

    @Test
    void failingProbesAreReportedAndAvailableBackendsOnlyEnableTheirAlgorithms(@TempDir Path dir) throws Exception {
        Path services = dir.resolve("META-INF/services");
        Files.createDirectories(services);
        Files.writeString(services.resolve(GpuBackend.class.getName()),
                BrokenBackend.class.getName() + "\n" + ProbeOnlyBackend.class.getName() + "\n", StandardCharsets.UTF_8);
        try (URLClassLoader loader = new URLClassLoader(new URL[] {dir.toUri().toURL()},
                getClass().getClassLoader())) {
            GpuStatus status = GpuStatus.detect(loader);
            assertEquals(1, status.available().size());
            assertTrue(status.unavailableReasons().get(0).contains("broken-test-backend"));
            assertTrue(status.unavailableReasons().get(0).contains("UnsatisfiedLinkError"));

            AlgorithmRegistry registry = AlgorithmRegistry.of(new BuiltInAlgorithms().algorithms(), status);
            assertTrue(registry.info(AlgorithmId.of("matrix.multiply")).orElseThrow().capabilities()
                    .supports(ExecutionMode.GPU));
            assertFalse(registry.info(AlgorithmId.of("sort.merge")).orElseThrow().capabilities()
                    .supports(ExecutionMode.GPU), "GPU is advertised only for algorithms the backend implements");
        }
    }

    /** Test double whose native library is "missing". */
    public static final class BrokenBackend implements GpuBackend {
        @Override
        public String name() {
            return "broken-test-backend";
        }

        @Override
        public Probe probe() {
            throw new UnsatisfiedLinkError("libOpenCL.so not found");
        }

        @Override
        public boolean supports(AlgorithmId algorithm) {
            return true;
        }

        @Override
        public <I, R> R execute(ComputeAlgorithm<I, R> algorithm, I input, ExecutionContext context) {
            throw new UnsupportedOperationException("test double");
        }
    }

    /** Detection-only test double: reports a device but executes nothing. */
    public static final class ProbeOnlyBackend implements GpuBackend {
        @Override
        public String name() {
            return "probe-only-test-backend";
        }

        @Override
        public Probe probe() {
            return new Probe(true, List.of(new GpuDevice("Test device", "Test", "OpenCL 3.0", 1L << 30, 8, true)),
                    "");
        }

        @Override
        public boolean supports(AlgorithmId algorithm) {
            return algorithm.value().equals("matrix.multiply");
        }

        @Override
        public <I, R> R execute(ComputeAlgorithm<I, R> algorithm, I input, ExecutionContext context) {
            throw new UnsupportedOperationException("detection test double; executes nothing");
        }
    }

    @Test
    void testDoublesNeverExecute() {
        assertThrows(UnsupportedOperationException.class,
                () -> new ProbeOnlyBackend().execute(null, null, ExecutionContext.unmanaged()));
    }
}
