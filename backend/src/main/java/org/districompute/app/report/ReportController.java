package org.districompute.app.report;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.districompute.app.analysis.AnalysisService;
import org.districompute.app.analysis.AnalysisViews.CompareRequest;
import org.districompute.app.api.ApiException;
import org.districompute.app.experiment.ExperimentService.ExperimentFilter;
import org.districompute.app.report.ReportService.ReportFile;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Report downloads ({@code docs/reports.md}). They are GET requests so that a plain link downloads them with the
 * session cookie; they change nothing except the audit log.
 */
@RestController
public class ReportController {

    private static final Set<String> STATUSES = Set.of("QUEUED", "RUNNING", "COMPLETED", "FAILED", "CANCELLED");

    private final ReportService reports;

    ReportController(ReportService reports) {
        this.reports = reports;
    }

    /**
     * The report of one experiment.
     *
     * @param id experiment id
     * @param format pdf, csv or json
     * @return the file
     */
    @GetMapping("/api/v1/experiments/{id}/report")
    public ResponseEntity<byte[]> experiment(@PathVariable String id,
            @RequestParam(defaultValue = "pdf") String format) {
        return download(reports.experiment(id, ReportFormat.parse(format)));
    }

    /**
     * The report of a comparison ({@code POST /api/v1/analysis/compare} as a file).
     *
     * @param ids comma-separated experiment ids
     * @param baseline baseline id
     * @param basis EXECUTION_TIME or WALL_TIME
     * @param format pdf, csv or json
     * @return the file
     */
    @GetMapping("/api/v1/analysis/report")
    public ResponseEntity<byte[]> comparison(@RequestParam String ids, @RequestParam(required = false) String baseline,
            @RequestParam(required = false) String basis, @RequestParam(defaultValue = "pdf") String format) {
        List<String> list = Arrays.stream(ids.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        if (list.size() < 2 || list.size() > AnalysisService.MAX_COMPARED) {
            throw ApiException.badRequest("INVALID_SELECTION", "Compare 2 to " + AnalysisService.MAX_COMPARED
                    + " experiments");
        }
        if (basis != null && !Set.of(AnalysisService.EXECUTION_TIME, AnalysisService.WALL_TIME).contains(basis)) {
            throw ApiException.badRequest("INVALID_BASIS", "Use basis EXECUTION_TIME or WALL_TIME");
        }
        return download(reports.comparison(new CompareRequest(list, baseline, basis), ReportFormat.parse(format)));
    }

    /**
     * The experiment history with the history page's filters, as CSV or JSON.
     *
     * @param format csv or json
     * @param search text in name, id or algorithm
     * @param status status
     * @param algorithm algorithm id
     * @param mode execution mode
     * @param category category
     * @param from created at or after
     * @param to created before
     * @param batch batch id
     * @param mine only the caller's (administrators)
     * @return the file
     */
    @GetMapping("/api/v1/experiments/export")
    public ResponseEntity<byte[]> history(@RequestParam(defaultValue = "csv") String format,
            @RequestParam(required = false) String search, @RequestParam(required = false) String status,
            @RequestParam(required = false) String algorithm, @RequestParam(required = false) String mode,
            @RequestParam(required = false) String category, @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to, @RequestParam(required = false) String batch,
            @RequestParam(defaultValue = "false") boolean mine) {
        if (status != null && !STATUSES.contains(status)) {
            throw ApiException.badRequest("INVALID_FILTER", "Unknown status " + status);
        }
        return download(reports.history(new ExperimentFilter(search, status, algorithm, mode, category, from, to,
                batch, mine), ReportFormat.parse(format)));
    }

    private static ResponseEntity<byte[]> download(ReportFile file) {
        return ResponseEntity.ok()
                .contentType(file.format().mediaType())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .cacheControl(CacheControl.noStore())
                .body(file.content());
    }
}
