package org.districompute.distributed.simulation;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Faults a worker injects into its own behaviour, for experiments only ({@code docs/fault-tolerance.md},
 * "Fault simulation"). Nothing happens unless {@link #enabled()} is {@code true}; such a worker reports
 * {@code faultSimulation: true} at registration, and coordinators refuse it unless they explicitly allow fault
 * simulation. Faults only affect the worker's own process and its own connection.
 *
 * @param enabled master switch
 * @param seed seed of the random choices, so an experiment can be repeated exactly
 * @param responseDelayMillis extra time before each result is sent (the slot stays busy)
 * @param messageLatencyMillis delay added to every message the worker sends (simulated slow link)
 * @param slowdownFactor kernel time is stretched by this factor (1 = normal speed), simulating a slower machine
 * @param disconnectAfterTasks drop the connection abruptly after receiving this many tasks (0 = never); the worker
 *     then reconnects like after a network failure
 * @param stopHeartbeatsAfterMillis stop sending heartbeats this long after each registration (0 = never)
 * @param hangProbability probability that an attempt never answers, not even to {@code CANCEL}
 * @param failProbability probability that an attempt reports a (retryable) kernel failure
 * @param corruptProbability probability that a result is corrupted in transit (checksum no longer matches)
 * @param duplicateProbability probability that a result is sent twice
 * @param capacitySchedule capacity changes: after {@code afterMillis} since start, use {@code capacity} slots
 */
public record FaultProfile(boolean enabled, long seed, long responseDelayMillis, long messageLatencyMillis,
        double slowdownFactor, int disconnectAfterTasks, long stopHeartbeatsAfterMillis, double hangProbability,
        double failProbability, double corruptProbability, double duplicateProbability,
        List<CapacityChange> capacitySchedule) {

    /** No fault simulation. */
    public static final FaultProfile NONE = new FaultProfile(false, 0, 0, 0, 1.0, 0, 0, 0, 0, 0, 0, List.of());

    private static final Set<String> KEYS = Set.of("enabled", "seed", "response-delay-ms", "message-latency-ms",
            "slowdown", "disconnect-after-tasks", "stop-heartbeats-after-ms", "hang-probability", "fail-probability",
            "corrupt-probability", "duplicate-probability", "capacity-schedule");

    /**
     * A scheduled capacity change.
     *
     * @param afterMillis time since the worker started
     * @param capacity new concurrency limit
     */
    public record CapacityChange(long afterMillis, int capacity) {

        /** Validates the components. */
        public CapacityChange {
            if (afterMillis < 0 || capacity < 1) {
                throw new IllegalArgumentException("A capacity change needs afterMillis >= 0 and capacity >= 1");
            }
        }
    }

    /** Validates the components. */
    public FaultProfile {
        if (responseDelayMillis < 0 || messageLatencyMillis < 0 || disconnectAfterTasks < 0
                || stopHeartbeatsAfterMillis < 0) {
            throw new IllegalArgumentException("Fault delays and counts must not be negative");
        }
        if (!(slowdownFactor >= 1.0) || slowdownFactor > 100) {
            throw new IllegalArgumentException("slowdown must be between 1 and 100");
        }
        for (double p : new double[] {hangProbability, failProbability, corruptProbability, duplicateProbability}) {
            if (!(p >= 0 && p <= 1)) {
                throw new IllegalArgumentException("Probabilities must be between 0 and 1");
            }
        }
        capacitySchedule = List.copyOf(capacitySchedule == null ? List.of() : capacitySchedule);
    }

    /**
     * Reads a profile from a properties file ({@code enabled=true}, {@code slowdown=3}, ...).
     *
     * @param file the file
     * @return the profile
     * @throws IOException if the file cannot be read
     * @throws IllegalArgumentException for unknown keys or invalid values
     */
    public static FaultProfile load(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return of(properties);
    }

    /**
     * Builds a profile from properties.
     *
     * @param p the properties
     * @return the profile
     */
    public static FaultProfile of(Properties p) {
        Set<String> unknown = new TreeSet<>(p.stringPropertyNames());
        unknown.removeAll(KEYS);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown fault profile keys: " + unknown + "; known: "
                    + new TreeSet<>(KEYS));
        }
        try {
            return new FaultProfile(Boolean.parseBoolean(p.getProperty("enabled", "false").strip()),
                    Long.parseLong(p.getProperty("seed", "1").strip()),
                    Long.parseLong(p.getProperty("response-delay-ms", "0").strip()),
                    Long.parseLong(p.getProperty("message-latency-ms", "0").strip()),
                    Double.parseDouble(p.getProperty("slowdown", "1").strip()),
                    Integer.parseInt(p.getProperty("disconnect-after-tasks", "0").strip()),
                    Long.parseLong(p.getProperty("stop-heartbeats-after-ms", "0").strip()),
                    Double.parseDouble(p.getProperty("hang-probability", "0").strip()),
                    Double.parseDouble(p.getProperty("fail-probability", "0").strip()),
                    Double.parseDouble(p.getProperty("corrupt-probability", "0").strip()),
                    Double.parseDouble(p.getProperty("duplicate-probability", "0").strip()),
                    schedule(p.getProperty("capacity-schedule", "")));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid number in fault profile: " + e.getMessage(), e);
        }
    }

    /**
     * A short description of the active faults, for logs and capability reports.
     *
     * @return the description
     */
    public String describe() {
        if (!enabled) {
            return "none";
        }
        List<String> parts = new ArrayList<>();
        add(parts, responseDelayMillis > 0, "response delay " + responseDelayMillis + " ms");
        add(parts, messageLatencyMillis > 0, "latency " + messageLatencyMillis + " ms");
        add(parts, slowdownFactor > 1, String.format(Locale.ROOT, "slowdown x%.1f", slowdownFactor));
        add(parts, disconnectAfterTasks > 0, "disconnect after " + disconnectAfterTasks + " tasks");
        add(parts, stopHeartbeatsAfterMillis > 0, "heartbeats stop after " + stopHeartbeatsAfterMillis + " ms");
        add(parts, hangProbability > 0, "hang p=" + hangProbability);
        add(parts, failProbability > 0, "fail p=" + failProbability);
        add(parts, corruptProbability > 0, "corrupt p=" + corruptProbability);
        add(parts, duplicateProbability > 0, "duplicate p=" + duplicateProbability);
        add(parts, !capacitySchedule.isEmpty(), "capacity schedule " + capacitySchedule.stream()
                .map(c -> c.afterMillis() + "ms:" + c.capacity()).toList());
        return parts.isEmpty() ? "enabled, no faults configured" : String.join(", ", parts) + " (seed " + seed + ")";
    }

    private static void add(List<String> parts, boolean condition, String text) {
        if (condition) {
            parts.add(text);
        }
    }

    private static List<CapacityChange> schedule(String text) {
        List<CapacityChange> changes = new ArrayList<>();
        for (String item : text.split(",")) {
            String trimmed = item.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException("capacity-schedule entries are afterMillis:capacity, got " + item);
            }
            changes.add(new CapacityChange(Long.parseLong(trimmed.substring(0, colon).strip()),
                    Integer.parseInt(trimmed.substring(colon + 1).strip())));
        }
        return changes;
    }
}
