package org.districompute.core.metrics;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.districompute.core.CoreVersion;

/**
 * Hardware and software environment of a measurement (shared rule 7: benchmarks must record their conditions).
 *
 * <p>Deliberately excluded for privacy: host name, user name, file paths and JVM options that contain paths. The
 * distributed runtime identifies workers itself.
 *
 * @param coreVersion DistriCompute-Core version
 * @param javaVersion {@code java.version}
 * @param javaVendor {@code java.vendor}
 * @param jvmName {@code java.vm.name}
 * @param jvmVersion {@code java.vm.version}
 * @param osName {@code os.name}
 * @param osVersion {@code os.version}
 * @param osArch {@code os.arch}
 * @param availableProcessors processors available to the JVM (respects container CPU limits)
 * @param maxHeapBytes maximum heap size
 * @param totalPhysicalMemoryBytes physical memory of the machine or container, or {@code -1}
 * @param cpuModel CPU model name, or {@code "unknown"}
 * @param jvmOptions heap and GC related {@code -X} and {@code -XX} options without paths
 * @param capturedAt when the snapshot was taken
 */
public record EnvironmentInfo(
        String coreVersion,
        String javaVersion,
        String javaVendor,
        String jvmName,
        String jvmVersion,
        String osName,
        String osVersion,
        String osArch,
        int availableProcessors,
        long maxHeapBytes,
        long totalPhysicalMemoryBytes,
        String cpuModel,
        List<String> jvmOptions,
        Instant capturedAt) {

    /** Validates and copies the components. */
    public EnvironmentInfo {
        Objects.requireNonNull(coreVersion, "coreVersion");
        jvmOptions = List.copyOf(jvmOptions);
        Objects.requireNonNull(capturedAt, "capturedAt");
    }

    /**
     * Captures the current environment.
     *
     * @return the environment snapshot
     */
    public static EnvironmentInfo capture() {
        Runtime runtime = Runtime.getRuntime();
        List<String> options = ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(arg -> arg.startsWith("-X"))
                .filter(arg -> !arg.contains("/") && !arg.contains("\\"))
                .toList();
        return new EnvironmentInfo(CoreVersion.version(), System.getProperty("java.version"),
                System.getProperty("java.vendor"), System.getProperty("java.vm.name"),
                System.getProperty("java.vm.version"), System.getProperty("os.name"), System.getProperty("os.version"),
                System.getProperty("os.arch"), runtime.availableProcessors(), runtime.maxMemory(),
                physicalMemoryBytes(), readCpuModel(), options, Instant.now());
    }

    private static long physicalMemoryBytes() {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean hotspot) {
            return hotspot.getTotalMemorySize();
        }
        return -1;
    }

    private static String readCpuModel() {
        Path cpuInfo = Path.of("/proc/cpuinfo");
        if (Files.isReadable(cpuInfo)) {
            try (Stream<String> lines = Files.lines(cpuInfo, StandardCharsets.UTF_8)) {
                Optional<String> model = lines.filter(line -> line.startsWith("model name"))
                        .map(line -> line.substring(line.indexOf(':') + 1).trim())
                        .findFirst();
                if (model.isPresent()) {
                    return model.get();
                }
            } catch (IOException | RuntimeException e) {
                return "unknown";
            }
        }
        String identifier = System.getenv("PROCESSOR_IDENTIFIER");
        return identifier == null || identifier.isBlank() ? "unknown" : identifier;
    }
}
