package org.districompute.distributed.cli;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.districompute.distributed.communication.TlsConfig;
import org.districompute.distributed.simulation.FaultProfile;
import org.districompute.distributed.worker.WorkerConfig;
import org.districompute.distributed.worker.WorkerNode;

/** {@code worker}: runs a worker until the process is stopped or the coordinator ends it. */
public final class WorkerCommand {

    /** Options accepted by the command. */
    public static final Set<String> OPTIONS = Set.of("coordinator", "id", "secret-file", "max-concurrent-tasks",
            "max-task-memory-mb", "max-message-bytes", "labels", "algorithms", "tls-truststore",
            "tls-truststore-password", "tls-no-hostname-verification", "drain-timeout-ms", "connect-timeout-ms",
            "fault-profile");
    /** Boolean options of the command. */
    public static final Set<String> FLAGS = Set.of("tls-no-hostname-verification");

    private WorkerCommand() {
    }

    /**
     * Builds the configuration from options.
     *
     * @param options parsed options
     * @return the configuration
     */
    public static WorkerConfig config(Options options) {
        options.requireKnown(OPTIONS);
        String coordinator = options.get("coordinator", null);
        if (coordinator == null) {
            throw new IllegalArgumentException("--coordinator <host:port> is required");
        }
        int colon = coordinator.lastIndexOf(':');
        if (colon <= 0 || colon == coordinator.length() - 1) {
            throw new IllegalArgumentException("--coordinator must be host:port, got " + coordinator);
        }
        int port;
        try {
            port = Integer.parseInt(coordinator.substring(colon + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--coordinator must be host:port, got " + coordinator, e);
        }
        WorkerConfig.Builder builder = WorkerConfig.builder(coordinator.substring(0, colon), port,
                Secrets.resolve(options))
                .maxConcurrentTasks(options.getInt("max-concurrent-tasks", Runtime.getRuntime().availableProcessors()))
                .maxMessageBytes(options.getInt("max-message-bytes", 64 << 20))
                .drainTimeout(options.getMillis("drain-timeout-ms", Duration.ofSeconds(30)))
                .timeouts(options.getMillis("connect-timeout-ms", Duration.ofSeconds(5)), Duration.ofSeconds(10));
        if (options.has("id")) {
            builder.workerId(options.get("id", null));
        }
        if (options.has("max-task-memory-mb")) {
            builder.maxTaskMemoryBytes(options.getInt("max-task-memory-mb", 0) * 1024L * 1024L);
        }
        for (String label : list(options.get("labels", ""))) {
            int eq = label.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("--labels must be key=value pairs separated by commas");
            }
            builder.label(label.substring(0, eq), label.substring(eq + 1));
        }
        builder.allowedAlgorithms(list(options.get("algorithms", "")));
        if (options.has("fault-profile")) {
            Path file = Path.of(options.get("fault-profile", ""));
            try {
                builder.faults(FaultProfile.load(file));
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException("Cannot read the fault profile " + file + ": " + e.getMessage(), e);
            }
        }
        if (options.has("tls-truststore")) {
            builder.tls(new TlsConfig(true, null, null, Path.of(options.get("tls-truststore", "")),
                    options.get("tls-truststore-password", ""),
                    !options.getBoolean("tls-no-hostname-verification", false)));
        }
        return builder.build();
    }

    /**
     * Runs the command until the JVM shuts down or the coordinator stops the worker.
     *
     * @param options parsed options
     * @param out where status messages go
     * @return exit status
     */
    public static int run(Options options, PrintStream out) {
        WorkerConfig config = config(options);
        WorkerNode node = WorkerNode.start(config);
        Thread hook = new Thread(node::close, "worker-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        out.println("Worker " + config.workerId() + " connecting to " + config.coordinatorHost() + ":"
                + config.coordinatorPort() + " with " + config.maxConcurrentTasks() + " slots and "
                + node.capabilities().algorithms().size() + " algorithms. Press Ctrl+C to stop.");
        node.awaitStopped(Duration.ofDays(36_500));
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException e) {
            // The JVM is already shutting down; the hook has run.
            return 0;
        }
        out.println("Worker " + config.workerId() + " stopped" + (node.lastError() == null ? ""
                : " (last problem: " + node.lastError() + ")"));
        return 0;
    }

    private static Set<String> list(String value) {
        Set<String> items = new LinkedHashSet<>();
        Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).forEach(items::add);
        return items;
    }
}
