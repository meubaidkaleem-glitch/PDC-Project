package org.districompute.app.execution;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.districompute.app.config.AppProperties;
import org.districompute.app.config.JsonSupport;
import org.districompute.app.persistence.Enums.StorageFormat;
import org.districompute.core.algorithm.ComputeAlgorithm;
import org.districompute.core.algorithm.matrix.Matrix;
import org.districompute.core.registry.AlgorithmRegistry;
import org.districompute.core.result.TaskResult;
import org.districompute.distributed.task.ResultSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Stores the full result values the coordinator delivers ({@link ResultSink}) as files under
 * {@code <dataDir>/results/<experimentId>/run-<n>.(csv|json)}. Before writing, the value's digest is recomputed with
 * Core's {@code summarize} and compared with the digest Core verified, so a stored file is known to be the verified
 * result. Values larger than {@code districompute.storage.max-result-bytes} keep only Core's summary.
 */
@Service
public class ResultStore {

    private static final Logger LOG = LoggerFactory.getLogger(ResultStore.class);
    private static final Pattern SAFE = Pattern.compile("[0-9a-f-]{36}/run-[0-9]{1,3}\\.(csv|json)");

    private final Path root;
    private final long maxBytes;
    private final AlgorithmRegistry registry;
    private final JsonSupport json;
    private final Map<String, Stored> pending = new ConcurrentHashMap<>();

    ResultStore(AppProperties properties, AlgorithmRegistry registry, JsonSupport json) {
        this.root = properties.dataDir().resolve("results");
        this.maxBytes = properties.storage().maxResultBytes();
        this.registry = registry;
        this.json = json;
    }

    /**
     * What happened to a run's value.
     *
     * @param ref path relative to the result store, or {@code null} if not stored
     * @param format file format, or {@code null}
     * @param sizeBytes file size, or {@code null}
     * @param digestMatches whether the recomputed digest equals Core's verified digest
     * @param rows rows of a CSV result (matrix rows or array length), or {@code null}
     * @param columns columns of a CSV result, or {@code null}
     * @param note explanation when the value was not stored, or {@code null}
     */
    public record Stored(String ref, StorageFormat format, Long sizeBytes, Boolean digestMatches, Integer rows,
            Integer columns, String note) {
    }

    /**
     * A sink that stores the value of one run; the outcome is collected with {@link #take(String)}.
     *
     * @param experimentId experiment
     * @param runIndex run index
     * @param coreTaskId Core task id (key for {@link #take})
     * @return the sink
     */
    public ResultSink sinkFor(String experimentId, int runIndex, String coreTaskId) {
        return (result, value) -> pending.put(coreTaskId, store(experimentId, runIndex, result, value));
    }

    /**
     * The storage outcome of a run, once (removes it).
     *
     * @param coreTaskId Core task id
     * @return the outcome, or {@code null} if no value was delivered
     */
    public Stored take(String coreTaskId) {
        return pending.remove(coreTaskId);
    }

    Stored store(String experimentId, int runIndex, TaskResult result, Object value) {
        Boolean matches = digestMatches(result, value);
        if (Boolean.FALSE.equals(matches)) {
            LOG.error("The value delivered for {} does not have Core's verified digest; not storing it",
                    result.taskId());
            return new Stored(null, null, null, false, null, null,
                    "Not stored: the delivered value's digest differs from the verified digest");
        }
        long estimate = estimateBytes(value);
        if (estimate > maxBytes) {
            return new Stored(null, null, null, matches, null, null, "Not stored: about " + (estimate >> 20)
                    + " MiB, larger than the configured limit of " + (maxBytes >> 20) + " MiB");
        }
        boolean csv = value instanceof Matrix || value instanceof long[] || value instanceof double[];
        String ref = experimentId + "/run-" + runIndex + (csv ? ".csv" : ".json");
        Path file = root.resolve(ref);
        try {
            Files.createDirectories(file.getParent());
            int rows;
            int columns;
            if (value instanceof Matrix m) {
                writeMatrix(file, m);
                rows = m.rows();
                columns = m.cols();
            } else if (value instanceof long[] longs) {
                writeLongs(file, longs);
                rows = longs.length;
                columns = 1;
            } else if (value instanceof double[] doubles) {
                writeDoubles(file, doubles);
                rows = doubles.length;
                columns = 1;
            } else {
                Files.writeString(file, json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(value),
                        StandardCharsets.UTF_8);
                return new Stored(ref, StorageFormat.JSON, Files.size(file), matches, null, null, null);
            }
            return new Stored(ref, StorageFormat.CSV, Files.size(file), matches, rows, columns, null);
        } catch (IOException | RuntimeException e) {
            LOG.error("Could not store the result of {}", result.taskId(), e);
            return new Stored(null, null, null, matches, null, null, "Not stored: " + e.getMessage());
        }
    }

    /**
     * Resolves a stored result, refusing anything that is not a path this store generated.
     *
     * @param ref stored reference
     * @return the file
     */
    public Path resolve(String ref) {
        if (ref == null || !SAFE.matcher(ref).matches()) {
            throw new IllegalArgumentException("Not a result reference: " + ref);
        }
        return root.resolve(ref);
    }

    /**
     * Reads the first rows and columns of a CSV result.
     *
     * @param ref stored reference
     * @param maxRows rows to read
     * @param maxColumns columns per row to keep
     * @return the rows (header excluded)
     * @throws IOException if unreadable
     */
    public List<List<String>> head(String ref, int maxRows, int maxColumns) throws IOException {
        List<List<String>> out = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(resolve(ref), StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) {
                return out;
            }
            String line;
            while (out.size() < maxRows && (line = reader.readLine()) != null) {
                String[] cells = line.split(",", maxColumns + 1);
                List<String> row = new ArrayList<>();
                for (int i = 0; i < Math.min(maxColumns, cells.length); i++) {
                    row.add(cells[i]);
                }
                out.add(row);
            }
        }
        return out;
    }

    /**
     * Deletes the stored results of an experiment.
     *
     * @param experimentId experiment
     */
    public void deleteAll(String experimentId) {
        Path directory = root.resolve(experimentId);
        if (!Files.isDirectory(directory) || !experimentId.matches("[0-9a-f-]{36}")) {
            return;
        }
        try (var files = Files.list(directory)) {
            for (Path f : files.toList()) {
                Files.deleteIfExists(f);
            }
            Files.deleteIfExists(directory);
        } catch (IOException e) {
            LOG.warn("Could not delete the results of {}", experimentId, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Boolean digestMatches(TaskResult result, Object value) {
        if (result.result() == null) {
            return null;
        }
        try {
            ComputeAlgorithm<Object, Object> algorithm = (ComputeAlgorithm<Object, Object>) registry.resolve(
                    result.algorithm());
            return algorithm.summarize(value).digest().equals(result.result().digest());
        } catch (RuntimeException e) {
            LOG.warn("Could not recompute the digest of {}", result.taskId(), e);
            return null;
        }
    }

    private static long estimateBytes(Object value) {
        if (value instanceof Matrix m) {
            return (long) m.rows() * m.cols() * 24;
        }
        if (value instanceof long[] longs) {
            return longs.length * 21L;
        }
        if (value instanceof double[] doubles) {
            return doubles.length * 25L;
        }
        return 0;
    }

    private static void writeMatrix(Path file, Matrix m) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (int c = 0; c < m.cols(); c++) {
                out.write(c == 0 ? "c0" : ",c" + c);
            }
            out.newLine();
            double[] data = m.data();
            for (int r = 0; r < m.rows(); r++) {
                int base = r * m.cols();
                for (int c = 0; c < m.cols(); c++) {
                    if (c > 0) {
                        out.write(',');
                    }
                    out.write(Double.toString(data[base + c]));
                }
                out.newLine();
            }
        }
    }

    private static void writeLongs(Path file, long[] values) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write("value");
            out.newLine();
            for (long v : values) {
                out.write(Long.toString(v));
                out.newLine();
            }
        }
    }

    private static void writeDoubles(Path file, double[] values) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write("value");
            out.newLine();
            for (double v : values) {
                out.write(Double.toString(v));
                out.newLine();
            }
        }
    }
}
