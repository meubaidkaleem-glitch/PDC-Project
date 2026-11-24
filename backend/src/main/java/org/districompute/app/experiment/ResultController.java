package org.districompute.app.experiment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.districompute.app.api.ApiException;
import org.districompute.app.config.AppProperties;
import org.districompute.app.execution.ResultStore;
import org.districompute.app.experiment.ExperimentViews.ResultView;
import org.districompute.app.persistence.Enums.StorageFormat;
import org.districompute.app.persistence.Experiment;
import org.districompute.app.persistence.ExperimentResult;
import org.districompute.app.persistence.ExperimentResultRepository;
import org.districompute.app.persistence.ExperimentRun;
import org.districompute.app.persistence.ExperimentRunRepository;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Run results: Core's summary, a bounded preview, and the full stored value as a download (never loaded whole into
 * the browser).
 */
@RestController
@RequestMapping("/api/v1/experiments/{id}/runs/{runIndex}/result")
public class ResultController {

    private static final long JSON_PREVIEW_LIMIT = 256 * 1024;

    private final ExperimentAccess access;
    private final ExperimentRunRepository runs;
    private final ExperimentResultRepository results;
    private final ResultStore store;
    private final ExperimentService experiments;
    private final AppProperties.Storage storage;

    ResultController(ExperimentAccess access, ExperimentRunRepository runs, ExperimentResultRepository results,
            ResultStore store, ExperimentService experiments, AppProperties properties) {
        this.access = access;
        this.runs = runs;
        this.results = results;
        this.store = store;
        this.experiments = experiments;
        this.storage = properties.storage();
    }

    /**
     * The result of a run.
     *
     * @param id experiment
     * @param runIndex run
     * @return the result
     */
    @GetMapping
    public ResultView result(@PathVariable String id, @PathVariable int runIndex) {
        return experiments.resultView(record(id, runIndex), runIndex);
    }

    /**
     * The first rows and columns of a stored CSV result, or a small JSON result whole.
     *
     * @param id experiment
     * @param runIndex run
     * @param rows rows wanted (bounded by the configured preview size)
     * @param columns columns wanted (bounded likewise)
     * @return the preview
     * @throws IOException if the file cannot be read
     */
    @GetMapping("/preview")
    public Preview preview(@PathVariable String id, @PathVariable int runIndex,
            @RequestParam(required = false) Integer rows, @RequestParam(required = false) Integer columns)
            throws IOException {
        ExperimentResult result = record(id, runIndex);
        if (result.getStorageRef() == null) {
            throw ApiException.notFound("Stored value of run", runIndex);
        }
        if (result.getStorageFormat() == StorageFormat.JSON) {
            Path file = store.resolve(result.getStorageRef());
            if (Files.size(file) > JSON_PREVIEW_LIMIT) {
                return new Preview("JSON", null, null, List.of(), null, true);
            }
            return new Preview("JSON", null, null, List.of(), Files.readString(file, StandardCharsets.UTF_8), false);
        }
        int r = Math.min(rows == null ? storage.previewRows() : rows, 100);
        int c = Math.min(columns == null ? storage.previewColumns() : columns, 50);
        List<List<String>> head = store.head(result.getStorageRef(), Math.max(1, r), Math.max(1, c));
        Map<String, Object> summary = experiments.resultView(result, runIndex).summary();
        Integer totalRows = number(summary, "rows", "length");
        Integer totalColumns = number(summary, "cols", null);
        boolean truncated = totalRows == null || totalRows > head.size()
                || (totalColumns != null && !head.isEmpty() && totalColumns > head.get(0).size());
        return new Preview("CSV", totalRows, totalColumns == null ? 1 : totalColumns, head, null, truncated);
    }

    /**
     * The full stored value.
     *
     * @param id experiment
     * @param runIndex run
     * @return the file
     */
    @GetMapping("/download")
    public ResponseEntity<Resource> download(@PathVariable String id, @PathVariable int runIndex) {
        ExperimentResult result = record(id, runIndex);
        if (result.getStorageRef() == null) {
            throw ApiException.notFound("Stored value of run", runIndex);
        }
        Path file = store.resolve(result.getStorageRef());
        String extension = result.getStorageFormat() == StorageFormat.JSON ? "json" : "csv";
        String name = "result-" + id.substring(0, 8) + "-run" + runIndex + "." + extension;
        return ResponseEntity.ok().contentType(result.getStorageFormat() == StorageFormat.JSON
                ? MediaType.APPLICATION_JSON : new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build()
                        .toString())
                .body(new FileSystemResource(file));
    }

    private ExperimentResult record(String id, int runIndex) {
        Experiment experiment = access.require(id);
        ExperimentRun run = runs.findByExperimentIdOrderByRunIndexAsc(experiment.getId()).stream()
                .filter(r -> r.getRunIndex() == runIndex).findFirst()
                .orElseThrow(() -> ApiException.notFound("Run", runIndex));
        return results.findByRunId(run.getId()).orElseThrow(() -> ApiException.notFound("Result of run", runIndex));
    }

    private static Integer number(Map<String, Object> summary, String key, String fallback) {
        Object value = summary.get(key);
        if (value == null && fallback != null) {
            value = summary.get(fallback);
        }
        return value instanceof Number n ? n.intValue() : null;
    }

    /**
     * A bounded view of a result.
     *
     * @param format {@code CSV} or {@code JSON}
     * @param totalRows rows of the full value, if known
     * @param totalColumns columns of the full value, if known
     * @param rows the first rows (CSV)
     * @param json the whole JSON result, if small
     * @param truncated whether the preview shows only part of the value
     */
    public record Preview(String format, Integer totalRows, Integer totalColumns, List<List<String>> rows,
            String json, boolean truncated) {
    }
}
