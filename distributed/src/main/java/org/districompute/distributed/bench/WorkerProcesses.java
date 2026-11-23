package org.districompute.distributed.bench;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.districompute.distributed.DistributedCli;
import org.districompute.distributed.coordinator.Coordinator;
import org.districompute.distributed.registry.WorkerStatus;

/**
 * Worker processes started for a benchmark: separate JVMs running the {@code worker} command of this runtime, so
 * each has its own heap, garbage collector and JIT, and talks to the coordinator over loopback TCP.
 */
final class WorkerProcesses implements AutoCloseable {

    private final Path logDirectory;
    private final int heapMegabytes;
    private final String secret;
    private final Map<String, Process> processes = new LinkedHashMap<>();
    private final Map<String, Path> logs = new LinkedHashMap<>();

    WorkerProcesses(Path logDirectory, int heapMegabytes, String secret) {
        this.logDirectory = logDirectory;
        this.heapMegabytes = heapMegabytes;
        this.secret = secret;
    }

    /**
     * Starts a worker process.
     *
     * @param id worker id
     * @param port coordinator port on 127.0.0.1
     * @param slots concurrent attempts
     * @param slowdown kernel slowdown factor (1: no fault profile)
     * @param prefix log file prefix
     * @throws IOException if the process cannot be started
     */
    void start(String id, int port, int slots, double slowdown, String prefix) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>(List.of(java, "-Xmx" + heapMegabytes + "m", "-cp",
                classPath(), DistributedCli.class.getName(), "worker",
                "--coordinator", "127.0.0.1:" + port, "--id", id, "--max-concurrent-tasks", Integer.toString(slots)));
        if (slowdown > 1) {
            Path profile = logDirectory.resolve(prefix + "-" + id + ".faults.properties");
            Files.writeString(profile, String.format(Locale.ROOT,
                    "# Written by the benchmark: a machine %.1fx slower than the others.%nenabled=true%nseed=1%n"
                            + "slowdown=%.3f%n", slowdown, slowdown), StandardCharsets.UTF_8);
            command.add("--fault-profile");
            command.add(profile.toString());
        }
        Path log = logDirectory.resolve(prefix + "-" + id + ".log");
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile())
                .directory(new File(System.getProperty("java.io.tmpdir")));
        // The secret reaches the child through its environment, which only the same user can read.
        builder.environment().put("DISTRICOMPUTE_SECRET", secret);
        processes.put(id, builder.start());
        logs.put(id, log);
    }

    /**
     * This JVM's class path with absolute entries: the workers start in another directory, and
     * {@code java -jar relative/path.jar} gives a relative class path.
     *
     * @return the class path
     */
    static String classPath() {
        return java.util.Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(entry -> !entry.isBlank()).map(entry -> Path.of(entry).toAbsolutePath().toString())
                .collect(java.util.stream.Collectors.joining(File.pathSeparator));
    }

    /**
     * Waits until every started worker is {@code ACTIVE} and has sent a heartbeat (which proves that the process
     * itself completed its registration).
     *
     * @param coordinator the coordinator
     * @param timeout how long to wait
     * @throws IOException if a process exits or does not register in time
     * @throws InterruptedException if interrupted
     */
    void awaitRegistered(Coordinator coordinator, Duration timeout) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            for (Map.Entry<String, Process> p : processes.entrySet()) {
                if (!p.getValue().isAlive()) {
                    throw new IOException("Worker process " + p.getKey() + " exited with status "
                            + p.getValue().exitValue() + "; see " + logs.get(p.getKey()));
                }
            }
            long ready = coordinator.workers().stream().filter(w -> processes.containsKey(w.workerId()))
                    .filter(w -> w.status() == WorkerStatus.ACTIVE && w.load().freeMemoryBytes() > 0).count();
            if (ready == processes.size()) {
                return;
            }
            if (System.nanoTime() > deadline) {
                throw new IOException("Only " + ready + " of " + processes.size()
                        + " worker processes registered within " + timeout.toSeconds() + " s; see the logs in "
                        + logDirectory);
            }
            Thread.sleep(50);
        }
    }

    /** Stops the processes gracefully (SIGTERM), then forcibly if they do not exit within 30 s. */
    @Override
    public void close() {
        processes.values().forEach(Process::destroy);
        for (Process process : processes.values()) {
            try {
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        processes.clear();
    }
}
