package org.districompute.distributed.worker;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.districompute.core.registry.AlgorithmRegistry;
import org.districompute.distributed.communication.TlsConfig;
import org.districompute.distributed.protocol.Ids;
import org.districompute.distributed.protocol.WorkerCapabilities;
import org.districompute.distributed.security.SharedSecret;
import org.districompute.distributed.simulation.FaultProfile;

/**
 * Worker settings ({@code docs/worker-lifecycle.md}).
 *
 * @param workerId this worker's id; unique among the coordinator's connected workers
 * @param coordinatorHost coordinator host name or address
 * @param coordinatorPort coordinator worker port
 * @param secret the cluster's shared secret
 * @param tls TLS settings (a trust store for the coordinator's certificate)
 * @param maxConcurrentTasks attempts executed at the same time
 * @param maxTaskMemoryBytes memory one attempt may use; larger work units are rejected
 * @param maxMessageBytes largest frame accepted from the coordinator
 * @param connectTimeout TCP connection timeout
 * @param registrationTimeout time to wait for {@code REGISTER_ACK}
 * @param initialBackoff first delay before reconnecting
 * @param maxBackoff largest delay between reconnection attempts
 * @param drainTimeout on graceful shutdown, how long running attempts may take to finish
 * @param progressInterval minimum interval between {@code TASK_PROGRESS} messages of one attempt
 * @param labels free-form labels reported to the coordinator
 * @param allowedAlgorithms if not empty, only these algorithm ids are offered
 * @param registry Core algorithms installed on this worker
 * @param faults fault simulation profile ({@link FaultProfile#NONE} outside experiments)
 */
public record WorkerConfig(
        String workerId,
        String coordinatorHost,
        int coordinatorPort,
        SharedSecret secret,
        TlsConfig tls,
        int maxConcurrentTasks,
        long maxTaskMemoryBytes,
        int maxMessageBytes,
        Duration connectTimeout,
        Duration registrationTimeout,
        Duration initialBackoff,
        Duration maxBackoff,
        Duration drainTimeout,
        Duration progressInterval,
        Map<String, String> labels,
        Set<String> allowedAlgorithms,
        AlgorithmRegistry registry,
        FaultProfile faults) {

    /** Validates the settings. */
    public WorkerConfig {
        if (!Ids.matches(Ids.WORKER_ID, workerId)) {
            throw new IllegalArgumentException("workerId must be 1-64 characters [A-Za-z0-9._:-], got " + workerId);
        }
        Objects.requireNonNull(coordinatorHost, "coordinatorHost");
        Objects.requireNonNull(secret, "secret (the cluster's shared secret is required)");
        Objects.requireNonNull(tls, "tls");
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(faults, "faults");
        if (coordinatorPort < 1 || coordinatorPort > 65_535) {
            throw new IllegalArgumentException("coordinatorPort must be between 1 and 65535");
        }
        if (maxConcurrentTasks < 1 || maxConcurrentTasks > WorkerCapabilities.MAX_CONCURRENT_TASKS) {
            throw new IllegalArgumentException("maxConcurrentTasks must be between 1 and "
                    + WorkerCapabilities.MAX_CONCURRENT_TASKS);
        }
        if (maxTaskMemoryBytes < 1 || maxMessageBytes < 1024) {
            throw new IllegalArgumentException("maxTaskMemoryBytes must be positive and maxMessageBytes >= 1024");
        }
        for (Duration d : new Duration[] {connectTimeout, registrationTimeout, initialBackoff, maxBackoff,
            drainTimeout, progressInterval}) {
            if (d == null || d.isNegative()) {
                throw new IllegalArgumentException("Durations must be non-negative");
            }
        }
        labels = Map.copyOf(labels == null ? Map.of() : labels);
        allowedAlgorithms = Set.copyOf(allowedAlgorithms == null ? Set.of() : allowedAlgorithms);
    }

    /**
     * Starts a builder with the defaults.
     *
     * @param coordinatorHost coordinator host
     * @param coordinatorPort coordinator port
     * @param secret the cluster's shared secret
     * @return the builder
     */
    public static Builder builder(String coordinatorHost, int coordinatorPort, SharedSecret secret) {
        return new Builder(coordinatorHost, coordinatorPort, secret);
    }

    /**
     * A default worker id: the host name (restricted to the allowed characters) and the process id.
     *
     * @return the id
     */
    public static String defaultWorkerId() {
        String id = hostName().replaceAll("[^A-Za-z0-9._:-]", "-") + "-" + ProcessHandle.current().pid();
        return id.length() <= 64 ? id : id.substring(id.length() - 64);
    }

    static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            return "worker";
        }
    }

    /** Builds a {@link WorkerConfig}. */
    public static final class Builder {
        private final String coordinatorHost;
        private final int coordinatorPort;
        private final SharedSecret secret;
        private String workerId = defaultWorkerId();
        private TlsConfig tls = TlsConfig.DISABLED;
        private int maxConcurrentTasks = Runtime.getRuntime().availableProcessors();
        private Long maxTaskMemoryBytes;
        private int maxMessageBytes = 64 << 20;
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration registrationTimeout = Duration.ofSeconds(10);
        private Duration initialBackoff = Duration.ofMillis(500);
        private Duration maxBackoff = Duration.ofSeconds(10);
        private Duration drainTimeout = Duration.ofSeconds(30);
        private Duration progressInterval = Duration.ofMillis(500);
        private final Map<String, String> labels = new LinkedHashMap<>();
        private Set<String> allowedAlgorithms = Set.of();
        private AlgorithmRegistry registry;
        private FaultProfile faults = FaultProfile.NONE;

        private Builder(String coordinatorHost, int coordinatorPort, SharedSecret secret) {
            this.coordinatorHost = coordinatorHost;
            this.coordinatorPort = coordinatorPort;
            this.secret = secret;
        }

        /**
         * Sets the worker id.
         *
         * @param id the id
         * @return this builder
         */
        public Builder workerId(String id) {
            this.workerId = id;
            return this;
        }

        /**
         * Sets TLS.
         *
         * @param value TLS settings
         * @return this builder
         */
        public Builder tls(TlsConfig value) {
            this.tls = value;
            return this;
        }

        /**
         * Sets the concurrency limit.
         *
         * @param value attempts at the same time
         * @return this builder
         */
        public Builder maxConcurrentTasks(int value) {
            this.maxConcurrentTasks = value;
            return this;
        }

        /**
         * Sets the memory per attempt (default: 70 % of the heap divided by the concurrency limit).
         *
         * @param bytes the budget
         * @return this builder
         */
        public Builder maxTaskMemoryBytes(long bytes) {
            this.maxTaskMemoryBytes = bytes;
            return this;
        }

        /**
         * Sets the inbound frame limit.
         *
         * @param bytes the limit
         * @return this builder
         */
        public Builder maxMessageBytes(int bytes) {
            this.maxMessageBytes = bytes;
            return this;
        }

        /**
         * Sets the connection and registration timeouts.
         *
         * @param connect TCP connection timeout
         * @param registration time to wait for {@code REGISTER_ACK}
         * @return this builder
         */
        public Builder timeouts(Duration connect, Duration registration) {
            this.connectTimeout = connect;
            this.registrationTimeout = registration;
            return this;
        }

        /**
         * Sets the reconnection backoff.
         *
         * @param initial first delay
         * @param max largest delay
         * @return this builder
         */
        public Builder backoff(Duration initial, Duration max) {
            this.initialBackoff = initial;
            this.maxBackoff = max;
            return this;
        }

        /**
         * Sets the drain timeout for graceful shutdown.
         *
         * @param value the timeout
         * @return this builder
         */
        public Builder drainTimeout(Duration value) {
            this.drainTimeout = value;
            return this;
        }

        /**
         * Sets the minimum interval between progress messages.
         *
         * @param value the interval
         * @return this builder
         */
        public Builder progressInterval(Duration value) {
            this.progressInterval = value;
            return this;
        }

        /**
         * Adds a label.
         *
         * @param key label key
         * @param value label value
         * @return this builder
         */
        public Builder label(String key, String value) {
            this.labels.put(key, value);
            return this;
        }

        /**
         * Restricts the offered algorithms.
         *
         * @param ids algorithm ids (empty means all distributable algorithms)
         * @return this builder
         */
        public Builder allowedAlgorithms(Set<String> ids) {
            this.allowedAlgorithms = ids;
            return this;
        }

        /**
         * Sets the Core registry (default: the built-in algorithms).
         *
         * @param value the registry
         * @return this builder
         */
        public Builder registry(AlgorithmRegistry value) {
            this.registry = value;
            return this;
        }

        /**
         * Enables fault simulation (experiments only).
         *
         * @param value the profile
         * @return this builder
         */
        public Builder faults(FaultProfile value) {
            this.faults = value;
            return this;
        }

        /**
         * Builds the configuration.
         *
         * @return the validated configuration
         */
        public WorkerConfig build() {
            long memory = maxTaskMemoryBytes != null ? maxTaskMemoryBytes
                    : Math.max(1, (long) (Runtime.getRuntime().maxMemory() * 0.7) / Math.max(1, maxConcurrentTasks));
            return new WorkerConfig(workerId, coordinatorHost, coordinatorPort, secret, tls, maxConcurrentTasks,
                    memory, maxMessageBytes, connectTimeout, registrationTimeout, initialBackoff, maxBackoff,
                    drainTimeout, progressInterval, labels, allowedAlgorithms,
                    registry != null ? registry : AlgorithmRegistry.loadDefault(), faults);
        }
    }
}
