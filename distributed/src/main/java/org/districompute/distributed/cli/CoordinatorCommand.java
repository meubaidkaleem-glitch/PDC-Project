package org.districompute.distributed.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import org.districompute.distributed.communication.TlsConfig;
import org.districompute.distributed.coordinator.Coordinator;
import org.districompute.distributed.coordinator.CoordinatorConfig;

/** {@code coordinator}: runs a coordinator until the process is stopped (Ctrl+C or SIGTERM). */
public final class CoordinatorCommand {

    /** Options accepted by the command. */
    public static final Set<String> OPTIONS = Set.of("id", "bind", "port", "secret-file", "tls-keystore",
            "tls-keystore-password", "max-message-bytes", "registration-timeout-ms", "heartbeat-interval-ms",
            "suspect-after-ms", "heartbeat-timeout-ms", "max-workers", "allow-fault-simulation");
    /** Boolean options of the command. */
    public static final Set<String> FLAGS = Set.of("allow-fault-simulation");

    private CoordinatorCommand() {
    }

    /**
     * Builds the configuration from options.
     *
     * @param options parsed options
     * @return the configuration
     */
    public static CoordinatorConfig config(Options options) {
        options.requireKnown(OPTIONS);
        CoordinatorConfig.Builder builder = CoordinatorConfig.builder(Secrets.resolve(options))
                .bindAddress(options.get("bind", "0.0.0.0"))
                .port(options.getInt("port", CoordinatorConfig.DEFAULT_PORT))
                .maxMessageBytes(options.getInt("max-message-bytes", 64 << 20))
                .registrationTimeout(options.getMillis("registration-timeout-ms", Duration.ofSeconds(10)))
                .heartbeat(options.getMillis("heartbeat-interval-ms", Duration.ofSeconds(2)),
                        options.getMillis("suspect-after-ms", Duration.ofSeconds(5)),
                        options.getMillis("heartbeat-timeout-ms", Duration.ofSeconds(10)))
                .maxWorkers(options.getInt("max-workers", 256))
                .allowFaultSimulationWorkers(options.getBoolean("allow-fault-simulation", false));
        if (options.has("id")) {
            builder.coordinatorId(options.get("id", null));
        }
        if (options.has("tls-keystore")) {
            builder.tls(new TlsConfig(true, Path.of(options.get("tls-keystore", "")),
                    options.get("tls-keystore-password", ""), null, null, true));
        }
        return builder.build();
    }

    /**
     * Runs the command until the JVM shuts down.
     *
     * @param options parsed options
     * @param out where the startup message goes
     * @return exit status
     * @throws IOException if the coordinator cannot start
     */
    public static int run(Options options, PrintStream out) throws IOException {
        CoordinatorConfig config = config(options);
        Coordinator coordinator = Coordinator.start(config);
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            coordinator.close();
            stopped.countDown();
        }, "coordinator-shutdown"));
        out.println("Coordinator " + config.coordinatorId() + " is listening for workers on port "
                + coordinator.port() + (config.tls().enabled() ? " (TLS)" : "") + ". Press Ctrl+C to stop.");
        try {
            stopped.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }
}
