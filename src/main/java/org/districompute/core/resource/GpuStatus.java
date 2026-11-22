package org.districompute.core.resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import org.districompute.core.algorithm.AlgorithmId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Result of GPU capability detection: the backends that probed as available, and a reason for every backend that
 * did not. With no backend on the class path (the case for this release) GPU execution is unavailable and
 * {@link #summary()} says why.
 *
 * @param available backends with at least one usable device
 * @param unavailableReasons one human-readable reason per unavailable backend, or a single reason if none is
 *     installed
 */
public record GpuStatus(List<GpuBackend> available, List<String> unavailableReasons) {

    private static final Logger LOG = LoggerFactory.getLogger(GpuStatus.class);

    /** Copies the lists. */
    public GpuStatus {
        available = List.copyOf(available);
        unavailableReasons = List.copyOf(unavailableReasons);
    }

    /**
     * Status without any GPU backend.
     *
     * @return the status
     */
    public static GpuStatus none() {
        return new GpuStatus(List.of(), List.of("No GPU backend is installed (DistriCompute-Core ships none)"));
    }

    /**
     * Discovers and probes GPU backends with {@link ServiceLoader}. Backend failures, including linkage errors from
     * missing native libraries, are caught and reported, never propagated.
     *
     * @param classLoader class loader to search
     * @return the status
     */
    public static GpuStatus detect(ClassLoader classLoader) {
        List<GpuBackend> usable = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        try {
            for (GpuBackend backend : ServiceLoader.load(GpuBackend.class, classLoader)) {
                GpuBackend.Probe probe;
                try {
                    probe = Objects.requireNonNull(backend.probe(), "probe");
                } catch (RuntimeException | LinkageError e) {
                    probe = GpuBackend.Probe.unavailable("probe failed: " + e);
                    LOG.debug("GPU backend {} probe failed", backend.name(), e);
                }
                if (probe.available() && !probe.devices().isEmpty()) {
                    usable.add(backend);
                } else {
                    String reason = probe.reason().isBlank() ? "no usable device" : probe.reason();
                    reasons.add(backend.name() + ": " + reason);
                }
            }
        } catch (java.util.ServiceConfigurationError | LinkageError e) {
            reasons.add("GPU backend discovery failed: " + e);
        }
        if (usable.isEmpty() && reasons.isEmpty()) {
            return none();
        }
        return new GpuStatus(usable, reasons);
    }

    /**
     * The first available backend that supports an algorithm.
     *
     * @param algorithm algorithm identifier
     * @return the backend, if any
     */
    public java.util.Optional<GpuBackend> backendFor(AlgorithmId algorithm) {
        return available.stream().filter(backend -> backend.supports(algorithm)).findFirst();
    }

    /**
     * One-line explanation for reports and error messages.
     *
     * @return the summary
     */
    public String summary() {
        if (!available.isEmpty()) {
            return "GPU backends available: " + available.stream().map(GpuBackend::name).toList();
        }
        return String.join("; ", unavailableReasons);
    }
}
