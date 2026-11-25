package org.districompute.app.standalone;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.districompute.app.config.AppProperties;
import org.districompute.app.config.CoordinatorConfiguration;
import org.districompute.app.config.SecretFiles;
import org.districompute.app.persistence.AppUserRepository;
import org.districompute.core.registry.AlgorithmRegistry;
import org.districompute.distributed.coordinator.Coordinator;
import org.districompute.distributed.security.SharedSecret;
import org.districompute.distributed.worker.WorkerConfig;
import org.districompute.distributed.worker.WorkerNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Conveniences of a single-process installation ({@code docs/standalone.md}): workers started inside the
 * application, connected to its coordinator over loopback TCP with the cluster secret (so distributed runs work
 * without separate worker processes), and opening the interface in the default browser. Both are off unless
 * configured; the standalone profile turns them on, with a sign-in notice on the console for the person who started
 * the application.
 */
@Component
public class StandaloneSupport {

    private static final Logger LOG = LoggerFactory.getLogger(StandaloneSupport.class);

    private final AppProperties properties;
    private final Coordinator coordinator;
    private final AlgorithmRegistry registry;
    private final Environment environment;
    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final List<WorkerNode> workers = new ArrayList<>();

    StandaloneSupport(AppProperties properties, Coordinator coordinator, AlgorithmRegistry registry,
            Environment environment, AppUserRepository users, PasswordEncoder encoder) {
        this.properties = properties;
        this.coordinator = coordinator;
        this.registry = registry;
        this.environment = environment;
        this.users = users;
        this.encoder = encoder;
    }

    /**
     * Starts the local workers and opens the browser once the application is ready.
     *
     * @throws IOException if the cluster secret cannot be read
     */
    @EventListener(ApplicationReadyEvent.class)
    public void ready() throws IOException {
        AppProperties.Standalone s = properties.standalone();
        if (s.localWorkers() > 0) {
            int slots = s.localWorkerSlots() > 0 ? s.localWorkerSlots()
                    : Math.max(1, Runtime.getRuntime().availableProcessors() / s.localWorkers());
            SharedSecret secret = SharedSecret.of(CoordinatorConfiguration.clusterSecret(properties));
            for (int i = 1; i <= s.localWorkers(); i++) {
                WorkerNode node = WorkerNode.start(WorkerConfig.builder("127.0.0.1", coordinator.port(), secret)
                        .workerId("local-" + i).maxConcurrentTasks(slots).registry(registry)
                        .label("local", "true").build());
                workers.add(node);
            }
            for (WorkerNode node : workers) {
                if (!node.awaitRegistered(Duration.ofSeconds(20))) {
                    LOG.warn("A local worker did not register: {}", node.lastError());
                }
            }
            LOG.info("Started {} local workers with {} slots each", workers.size(), slots);
        }
        String url = "http://localhost:" + environment.getProperty("local.server.port", "8080") + "/";
        LOG.info("DistriCompute is ready: open {} in a browser", url);
        if (s.showSignIn()) {
            printSignIn(System.out, url);
        }
        if (s.openBrowser()) {
            open(url);
        }
    }

    /**
     * Prints where to sign in. The generated initial password is shown only while it is still the administrator's
     * password (read from its owner-only file and checked against the stored hash), and only on the console.
     *
     * @param out the console
     * @param url the interface's address
     * @throws IOException if the password file cannot be read
     */
    public void printSignIn(PrintStream out, String url) throws IOException {
        String admin = properties.security().adminUsername();
        Path file = properties.dataDir().resolve("initial-admin-password").toAbsolutePath();
        String password = Files.isRegularFile(file) ? SecretFiles.read(file) : null;
        boolean initial = password != null && users.findByUsername(admin)
                .filter(u -> encoder.matches(password, u.getPasswordHash())).isPresent();
        String rule = "=".repeat(76);
        out.println();
        out.println(rule);
        out.println("  DistriCompute is running at " + url);
        if (initial) {
            out.println("  First sign-in:  user " + admin + "   password " + password);
            out.println("  Change the password in Settings after signing in.");
        }
        out.println("  Data: " + properties.dataDir().toAbsolutePath());
        out.println("  Keep this window open while you use DistriCompute; close it or press Ctrl+C to stop.");
        out.println(rule);
        out.println();
        out.flush();
    }

    /** Stops the local workers before the coordinator closes. */
    @PreDestroy
    void stop() {
        workers.forEach(WorkerNode::close);
        workers.clear();
    }

    /** Opens a URL in the default browser with the operating system's own command; failures are only logged. */
    static void open(String url) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> command = os.contains("win") ? List.of("rundll32", "url.dll,FileProtocolHandler", url)
                : os.contains("mac") ? List.of("open", url) : List.of("xdg-open", url);
        try {
            new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException | RuntimeException e) {
            LOG.info("Could not open a browser ({}); open {} yourself", e.getMessage(), url);
        }
    }
}
