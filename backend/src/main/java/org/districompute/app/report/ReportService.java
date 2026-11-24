package org.districompute.app.report;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import org.districompute.app.analysis.AnalysisService;
import org.districompute.app.analysis.AnalysisViews.CompareRequest;
import org.districompute.app.analysis.AnalysisViews.Compared;
import org.districompute.app.analysis.AnalysisViews.Comparison;
import org.districompute.app.analysis.AnalysisViews.Issue;
import org.districompute.app.analysis.AnalysisViews.Stats;
import org.districompute.app.api.PageResponse;
import org.districompute.app.config.JsonSupport;
import org.districompute.app.experiment.ExperimentAccess;
import org.districompute.app.experiment.ExperimentService;
import org.districompute.app.experiment.ExperimentService.ExperimentFilter;
import org.districompute.app.experiment.ExperimentViews.ExperimentDetail;
import org.districompute.app.experiment.ExperimentViews.ExperimentSummary;
import org.districompute.app.experiment.ExperimentViews.MetricStats;
import org.districompute.app.experiment.ExperimentViews.MetricValue;
import org.districompute.app.experiment.ExperimentViews.ResultView;
import org.districompute.app.experiment.ExperimentViews.RunView;
import org.districompute.app.persistence.AppUser;
import org.districompute.app.persistence.Experiment;
import org.districompute.app.security.AuditService;
import org.districompute.app.security.CurrentUser;
import org.districompute.app.system.ComponentVersions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Builds experiment reports, comparison reports and history exports as PDF, CSV or JSON ({@code docs/reports.md}).
 * Reports are generated on request from stored data and not kept; generating one is audited.
 */
@Service
public class ReportService {

    /** Most experiments in a history export. */
    public static final int MAX_EXPORT = 5000;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter READABLE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
            .withZone(ZoneOffset.UTC);

    private final ExperimentAccess access;
    private final ExperimentService experiments;
    private final AnalysisService analysis;
    private final CurrentUser currentUser;
    private final AuditService audit;
    private final JsonSupport json;
    private final Clock clock;
    private final String version;

    ReportService(ExperimentAccess access, ExperimentService experiments, AnalysisService analysis,
            CurrentUser currentUser, AuditService audit, JsonSupport json, Clock clock,
            @Value("${districompute.version:0.1.0-SNAPSHOT}") String version) {
        this.access = access;
        this.experiments = experiments;
        this.analysis = analysis;
        this.currentUser = currentUser;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
        this.version = version;
    }

    /**
     * A generated report.
     *
     * @param filename suggested file name
     * @param format format
     * @param content bytes
     */
    public record ReportFile(String filename, ReportFormat format, byte[] content) {
    }

    /**
     * The report of one experiment.
     *
     * @param id experiment id
     * @param format format
     * @return the file
     */
    public ReportFile experiment(String id, ReportFormat format) {
        Experiment experiment = access.require(id);
        ExperimentDetail detail = experiments.detail(experiment);
        List<MetricValue> values = experiments.metricValues(experiment);
        Instant now = clock.instant();
        byte[] content = switch (format) {
            case JSON -> json(ordered("report", "experiment", "generatedAt", now, "generatedBy", username(),
                    "versions", versions(), "experiment", detail, "metricValues", values));
            case CSV -> experimentCsv(detail, values);
            case PDF -> experimentPdf(detail, now);
        };
        audit.record(username(), "REPORT_GENERATED", "EXPERIMENT", id, "format=" + format.extension(), null);
        return new ReportFile("districompute-experiment-" + id.substring(0, Math.min(8, id.length())) + "-"
                + STAMP.format(now) + "." + format.extension(), format, content);
    }

    /**
     * The report of a comparison.
     *
     * @param request what to compare
     * @param format format
     * @return the file
     */
    public ReportFile comparison(CompareRequest request, ReportFormat format) {
        Comparison comparison = analysis.compare(request);
        Instant now = clock.instant();
        byte[] content = switch (format) {
            case JSON -> json(ordered("report", "comparison", "generatedAt", now, "generatedBy", username(),
                    "versions", versions(), "comparison", comparison));
            case CSV -> comparisonCsv(comparison);
            case PDF -> comparisonPdf(comparison, now);
        };
        audit.record(username(), "REPORT_GENERATED", "COMPARISON", null,
                "format=" + format.extension() + " experiments=" + String.join(",", request.experimentIds()), null);
        return new ReportFile("districompute-comparison-" + STAMP.format(now) + "." + format.extension(), format,
                content);
    }

    /**
     * The experiment history matching a filter, newest first.
     *
     * @param filter the history page's filter
     * @param format CSV or JSON
     * @return the file
     */
    public ReportFile history(ExperimentFilter filter, ReportFormat format) {
        if (format == ReportFormat.PDF) {
            throw org.districompute.app.api.ApiException.badRequest("INVALID_FORMAT",
                    "The history is exported as csv or json");
        }
        AppUser user = currentUser.require();
        PageResponse<ExperimentSummary> page = experiments.list(filter,
                PageRequest.of(0, MAX_EXPORT, Sort.by("createdAt").descending()), user, currentUser.isAdmin());
        Instant now = clock.instant();
        byte[] content;
        if (format == ReportFormat.JSON) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("report", "history");
            out.put("generatedAt", now);
            out.put("generatedBy", username());
            out.put("versions", versions());
            out.put("filter", filter);
            out.put("totalItems", page.totalItems());
            out.put("truncated", page.totalItems() > page.items().size());
            out.put("experiments", page.items());
            content = json(out);
        } else {
            CsvTable csv = new CsvTable(List.of("id", "name", "owner", "category", "algorithm", "algorithm_version",
                    "execution_mode", "recommended_mode", "recommendation_accepted", "scheduling_policy", "status",
                    "repetitions", "completed_runs", "workers_used", "median_wall_time_ms", "created_at",
                    "started_at", "completed_at", "duration_ms", "error_message", "batch_id"));
            for (ExperimentSummary e : page.items()) {
                csv.row(java.util.Arrays.asList(e.id(), e.name(), e.owner(), e.category(), e.algorithm(),
                        e.algorithmVersion(), e.executionMode(), e.recommendedMode(), e.recommendationAccepted(),
                        e.schedulingPolicy(), e.status(), e.repetitions(), e.completedRuns(), e.workersUsed(),
                        e.medianWallTimeMs(), e.createdAt(), e.startedAt(), e.completedAt(), e.durationMs(),
                        e.errorMessage(), e.batchId()));
            }
            content = csv.bytes();
        }
        audit.record(username(), "REPORT_GENERATED", "HISTORY", null,
                "format=" + format.extension() + " rows=" + page.items().size(), null);
        return new ReportFile("districompute-experiments-" + STAMP.format(now) + "." + format.extension(), format,
                content);
    }

    // ---- experiment ----

    private byte[] experimentCsv(ExperimentDetail detail, List<MetricValue> values) {
        Map<String, String> columns = new TreeMap<>();
        Map<String, Long> sourcesPerName = new TreeMap<>();
        values.stream().map(v -> v.name() + "|" + v.source()).distinct()
                .forEach(k -> sourcesPerName.merge(k.substring(0, k.indexOf('|')), 1L, Long::sum));
        for (MetricValue v : values) {
            String column = v.name() + (v.unit() == null || v.unit().isBlank() ? "" : " [" + v.unit() + "]")
                    + (sourcesPerName.get(v.name()) > 1 ? " (" + v.source().toLowerCase(Locale.ROOT) + ")" : "");
            columns.put(v.name() + "|" + v.source(), column);
        }
        List<String> header = new ArrayList<>(List.of("experiment_id", "run_index", "core_task_id", "execution_mode",
                "status", "submitted_at", "started_at", "completed_at", "wall_time_ms", "threads", "partitions",
                "workers_used", "result_digest", "verification_passed", "verification_method", "error_code",
                "error_message"));
        header.addAll(columns.values());
        CsvTable csv = new CsvTable(header);
        for (RunView r : detail.runs()) {
            List<Object> row = new ArrayList<>(java.util.Arrays.asList(detail.summary().id(), r.runIndex(),
                    r.coreTaskId(), r.executionMode(), r.status(), r.submittedAt(), r.startedAt(), r.completedAt(),
                    r.wallTimeMs(), r.threads(), r.partitions(), r.workersUsed(), r.resultDigest(),
                    r.verificationPassed(), r.verificationMethod(), r.errorCode(), r.errorMessage()));
            for (String key : columns.keySet()) {
                row.add(values.stream().filter(v -> v.runIndex() == r.runIndex()
                        && (v.name() + "|" + v.source()).equals(key)).map(MetricValue::value).findFirst().orElse(null));
            }
            csv.row(row);
        }
        return csv.bytes();
    }

    private byte[] experimentPdf(ExperimentDetail d, Instant now) {
        ExperimentSummary s = d.summary();
        PdfReport pdf = new PdfReport("Experiment report", s.name(), footer(now), calendar(now));
        pdf.heading("Experiment");
        List<String[]> items = new ArrayList<>();
        items.add(kv("Identifier", s.id()));
        items.add(kv("Owner", s.owner()));
        items.add(kv("Algorithm", s.algorithm() + " " + s.algorithmVersion() + " (" + s.category().toLowerCase(Locale.ROOT)
                + ")"));
        items.add(kv("Execution mode", mode(s.executionMode())
                + (s.schedulingPolicy() == null ? "" : ", policy " + s.schedulingPolicy().toLowerCase(Locale.ROOT))));
        items.add(kv("Status", s.status().toLowerCase(Locale.ROOT)
                + (s.errorMessage() == null ? "" : ": " + s.errorMessage())));
        items.add(kv("Runs", s.completedRuns() + " completed of " + s.repetitions() + " planned"));
        items.add(kv("Created", time(s.createdAt())));
        items.add(kv("Started / completed", time(s.startedAt()) + " / " + time(s.completedAt())));
        items.add(kv("Duration", ms(s.durationMs() == null ? null : s.durationMs().doubleValue())));
        items.add(kv("Recommendation", s.recommendedMode() == null ? "no reliable recommendation"
                : mode(s.recommendedMode()) + (Boolean.TRUE.equals(s.recommendationAccepted()) ? ", followed"
                        : ", not followed") + (d.adviceBasis() == null ? "" : " (basis "
                                + d.adviceBasis().toLowerCase(Locale.ROOT) + ")")));
        if (d.overrideReason() != null) {
            items.add(kv("Reason for the override", d.overrideReason()));
        }
        items.add(kv("Input", d.inputSource().toLowerCase(Locale.ROOT)));
        Object dims = d.inputSummary().get("dimensions");
        if (dims instanceof Map<?, ?> m && !m.isEmpty()) {
            items.add(kv("Dimensions", m.toString().replaceAll("[{}]", "").replace("=", " ")));
        }
        if (d.inputSummary().get("estimatedOperations") instanceof Number n) {
            items.add(kv("Core's work estimate", String.format(Locale.ROOT, "%.3g %s", n.doubleValue(),
                    d.inputSummary().getOrDefault("operationUnit", "operations"))));
        }
        ComponentVersions v = versions();
        items.add(kv("Components", "Application " + v.application() + ", Core " + v.core() + ", Distributed "
                + v.distributed() + ", protocol " + v.protocol()));
        pdf.keyValues(items);
        if (d.description() != null && !d.description().isBlank()) {
            pdf.paragraph(d.description());
        }

        pdf.heading("Parameters");
        if (d.parameters().isEmpty()) {
            pdf.paragraph("None given; Core's defaults apply.");
        } else {
            pdf.keyValues(d.parameters().entrySet().stream().map(e -> kv(e.getKey(), value(e.getValue()))).toList());
            pdf.note("Parameters not listed use Core's documented defaults.");
        }
        List<String[]> settings = d.configuration().entrySet().stream().filter(e -> e.getValue() != null)
                .map(e -> kv(e.getKey(), "mode".equals(e.getKey()) ? mode(String.valueOf(e.getValue()))
                        : String.valueOf(e.getValue()))).toList();
        if (!settings.isEmpty()) {
            pdf.heading("Execution settings");
            pdf.keyValues(settings);
        }

        pdf.heading("Runs");
        pdf.table(new String[] {"#", "Status", "Wall time", "Threads", "Partitions", "Workers", "Verified",
            "Result digest"}, new float[] {3, 9, 8, 6, 7, 6, 6, 26},
                d.runs().stream().map(r -> new String[] {String.valueOf(r.runIndex()),
                    r.status().toLowerCase(Locale.ROOT) + (r.errorCode() == null ? "" : " (" + r.errorCode() + ")"),
                    ms(r.wallTimeMs()), str(r.threads()), str(r.partitions()), str(r.workersUsed()),
                    r.verificationPassed() == null ? "-" : r.verificationPassed() ? "yes" : "no",
                    r.resultDigest()}).toList(),
                false, false, false, false, false, false, false, true);
        List<RunView> timed = d.runs().stream().filter(r -> r.wallTimeMs() != null).toList();
        if (timed.size() > 1) {
            pdf.barChart("Wall time per run", timed.stream().map(r -> "Run " + r.runIndex()).toList(),
                    timed.stream().map(RunView::wallTimeMs).toList(), ReportService::ms, null);
        }
        List<RunView> failed = d.runs().stream().filter(r -> r.errorMessage() != null).toList();
        for (RunView r : failed) {
            pdf.note("Run " + r.runIndex() + ": " + r.errorMessage());
        }

        if (!d.metrics().isEmpty()) {
            pdf.heading("Measurements over completed runs");
            pdf.table(new String[] {"Metric", "Source", "Runs", "Median", "Minimum", "Maximum", "Std. dev."},
                    new float[] {16, 8, 4, 10, 10, 10, 10},
                    d.metrics().stream().map(m -> new String[] {m.name(), m.source().toLowerCase(Locale.ROOT),
                        String.valueOf(m.count()), metric(m.median(), m), metric(m.min(), m), metric(m.max(), m),
                        metric(m.stddev(), m)}).toList(), true);
            if (d.metrics().stream().allMatch(m -> m.count() < 2)) {
                pdf.note("One completed run: the variation of these measurements is unknown.");
            }
            pdf.note("Metric names and definitions as in docs/metrics.md. Wall time includes waiting for other runs "
                    + "and storing the result; Core's execution time does not.");
        }

        if (!d.results().isEmpty()) {
            pdf.heading("Results");
            for (ResultView r : d.results()) {
                List<String[]> res = new ArrayList<>();
                res.add(kv("Run", String.valueOf(r.runIndex())));
                res.add(kv("Verification", r.validationStatus().toLowerCase(Locale.ROOT)
                        + (r.validationMethod() == null ? "" : ", " + r.validationMethod())
                        + (r.validationMessage() == null ? "" : ": " + r.validationMessage())));
                res.add(kv("Digest", r.digest()));
                res.add(kv("Stored value", r.stored() ? r.format() + ", " + bytes(r.sizeBytes())
                        + (Boolean.TRUE.equals(r.storedDigestMatch()) ? ", digest matches the verified result" : "")
                        : "not stored" + (r.storageNote() == null ? "" : ": " + r.storageNote())));
                r.summary().forEach((k, val) -> res.add(kv(k, value(val))));
                pdf.keyValues(res);
            }
        }
        pdf.note("Generated by DistriCompute from stored measurements. Estimates are labelled as such; every other "
                + "figure was measured.");
        return pdf.finish();
    }

    // ---- comparison ----

    private byte[] comparisonCsv(Comparison c) {
        List<String> phases = new ArrayList<>();
        List<String> indicators = new ArrayList<>();
        for (Compared e : c.experiments()) {
            e.breakdown().keySet().stream().filter(k -> !phases.contains(k)).forEach(phases::add);
            e.indicators().keySet().stream().filter(k -> !indicators.contains(k)).forEach(indicators::add);
        }
        List<String> header = new ArrayList<>(List.of("number", "experiment_id", "name", "owner", "algorithm",
                "algorithm_version", "execution_mode", "threads", "workers", "completed_runs", "repetitions",
                "baseline", "basis", "median_ms", "min_ms", "max_ms", "cv", "wall_time_median_ms",
                "execution_time_median_ms", "waiting_median_ms", "speedup", "speedup_low", "speedup_high",
                "efficiency", "efficiency_basis", "result_digest", "results_consistent"));
        phases.forEach(p -> header.add("breakdown_" + p + "_ms"));
        indicators.forEach(header::add);
        CsvTable csv = new CsvTable(header);
        int n = 0;
        for (Compared e : c.experiments()) {
            Stats t = "EXECUTION_TIME".equals(c.basis()) ? e.executionTime() : e.wallTime();
            List<Object> row = new ArrayList<>(java.util.Arrays.asList(++n, e.id(), e.name(), e.owner(), e.algorithm(),
                    e.algorithmVersion(), e.mode(), e.threads(), e.workers(), e.completedRuns(), e.repetitions(),
                    e.id().equals(c.baselineId()), c.basis(), t == null ? null : t.median(), t == null ? null : t.min(),
                    t == null ? null : t.max(), t == null ? null : t.cv(), median(e.wallTime()),
                    median(e.executionTime()), median(e.waitingTime()), e.speedup(),
                    e.speedupRange() == null ? null : e.speedupRange().get(0),
                    e.speedupRange() == null ? null : e.speedupRange().get(1), e.efficiency(), e.efficiencyBasis(),
                    e.resultDigest(), e.resultsConsistent()));
            phases.forEach(p -> row.add(e.breakdown().get(p)));
            indicators.forEach(i -> row.add(e.indicators().get(i)));
            csv.row(row);
        }
        return csv.bytes();
    }

    private byte[] comparisonPdf(Comparison c, Instant now) {
        String basis = "EXECUTION_TIME".equals(c.basis()) ? "Core execution time" : "wall time";
        PdfReport pdf = new PdfReport("Comparison report", c.experiments().size() + " experiments compared by "
                + basis, footer(now), calendar(now));
        Compared baseline = c.experiments().stream().filter(e -> e.id().equals(c.baselineId())).findFirst()
                .orElse(null);
        pdf.keyValues(List.of(kv("Basis", c.basisDescription()),
                kv("Baseline", baseline == null ? "none" : baseline.name() + " (" + c.baselineReason() + ")"),
                kv("Comparable", c.comparable() ? "yes" : "no: see the issues below"),
                kv("Speedup", "baseline median / experiment median"),
                kv("Efficiency", "speedup / threads (parallel CPU) or / workers (distributed CPU), against a "
                        + "sequential baseline")));

        pdf.heading("Findings");
        c.summary().forEach(pdf::bullet);

        if (!c.issues().isEmpty()) {
            pdf.heading("Issues and caveats");
            for (String severity : List.of("ERROR", "WARNING", "INFO")) {
                for (Issue i : c.issues()) {
                    if (i.severity().equals(severity)) {
                        pdf.bullet(severity.charAt(0) + severity.substring(1).toLowerCase(Locale.ROOT) + ": "
                                + i.message());
                    }
                }
            }
        }

        pdf.heading("Experiments");
        List<String[]> rows = new ArrayList<>();
        int n = 0;
        for (Compared e : c.experiments()) {
            Stats t = "EXECUTION_TIME".equals(c.basis()) ? e.executionTime() : e.wallTime();
            rows.add(new String[] {String.valueOf(++n), e.name() + (e.id().equals(c.baselineId()) ? " (baseline)" : ""),
                mode(e.mode()) + units(e), e.completedRuns() + "/" + e.repetitions(), t == null ? "-" : ms(t.median()),
                t == null ? "-" : ms(t.min()) + " - " + ms(t.max()),
                t == null || t.cv() == null ? "-" : String.format(Locale.ROOT, "%.1f %%", t.cv() * 100),
                e.speedup() == null ? "-" : String.format(Locale.ROOT, "%.2fx", e.speedup()),
                e.efficiency() == null ? "-" : String.format(Locale.ROOT, "%.0f %% per %s", e.efficiency() * 100,
                        e.efficiencyBasis())});
        }
        pdf.table(new String[] {"#", "Experiment", "Mode", "Runs", "Median", "Fastest - slowest", "Variation",
            "Speedup", "Efficiency"}, new float[] {3, 19, 16, 5, 9, 14, 10, 8, 11}, rows);

        List<Compared> measured = c.experiments().stream()
                .filter(e -> ("EXECUTION_TIME".equals(c.basis()) ? e.executionTime() : e.wallTime()) != null).toList();
        if (!measured.isEmpty()) {
            pdf.barChart("Median " + basis, measured.stream().map(e -> (c.experiments().indexOf(e) + 1) + ". " + e.name()).toList(),
                    measured.stream().map(e -> ("EXECUTION_TIME".equals(c.basis()) ? e.executionTime()
                            : e.wallTime()).median()).toList(), ReportService::ms, null);
        }
        List<Compared> sped = c.experiments().stream().filter(e -> e.speedup() != null).toList();
        if (c.comparable() && !sped.isEmpty()) {
            pdf.barChart("Speedup over the baseline (dashed line: 1, the baseline)", sped.stream().map(e -> (c.experiments().indexOf(e) + 1) + ". " + e.name()).toList(),
                    sped.stream().map(Compared::speedup).toList(),
                    v -> String.format(Locale.ROOT, "%.2fx", v), 1.0);
        }

        List<String> phases = new ArrayList<>();
        measured.forEach(e -> e.breakdown().keySet().stream().filter(k -> !phases.contains(k)).forEach(phases::add));
        if (!phases.isEmpty()) {
            pdf.heading("Where the wall time goes (medians)");
            List<String> headers = new ArrayList<>(List.of("#"));
            phases.forEach(p -> headers.add(p.replace("_time", "").replace('_', ' ')));
            float[] widths = new float[headers.size()];
            java.util.Arrays.fill(widths, 10);
            widths[0] = 3;
            pdf.table(headers.toArray(String[]::new), widths, measured.stream().map(e -> {
                List<String> row = new ArrayList<>(List.of(String.valueOf(c.experiments().indexOf(e) + 1)));
                phases.forEach(p -> row.add(e.breakdown().containsKey(p) ? ms(e.breakdown().get(p)) : "-"));
                return row.toArray(String[]::new);
            }).toList());
        }
        pdf.note("Generated by DistriCompute from stored measurements of completed runs (docs/analysis.md).");
        return pdf.finish();
    }

    // ---- helpers ----

    private static Map<String, Object> ordered(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }

    private byte[] json(Object value) {
        return json.write(value).getBytes(StandardCharsets.UTF_8);
    }

    private ComponentVersions versions() {
        return ComponentVersions.current(version);
    }

    private String username() {
        return currentUser.require().getUsername();
    }

    private String footer(Instant now) {
        return "DistriCompute " + version + "  -  generated " + READABLE.format(now) + " by " + username();
    }

    private static Calendar calendar(Instant now) {
        GregorianCalendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        calendar.setTimeInMillis(now.toEpochMilli());
        return calendar;
    }

    private static String[] kv(String key, String value) {
        return new String[] {key, value};
    }

    private static String time(Instant instant) {
        return instant == null ? "-" : READABLE.format(instant);
    }

    private static String str(Object value) {
        return value == null ? "-" : value.toString();
    }

    private static Double median(Stats stats) {
        return stats == null ? null : stats.median();
    }

    static String mode(String mode) {
        return switch (mode) {
            case "SEQUENTIAL_CPU" -> "Sequential CPU";
            case "MULTITHREADED_CPU" -> "Parallel CPU (threads)";
            case "DISTRIBUTED_CPU" -> "Distributed CPU (workers)";
            default -> mode;
        };
    }

    private static String units(Compared e) {
        if (e.threads() != null && "MULTITHREADED_CPU".equals(e.mode())) {
            return ", " + e.threads() + " threads";
        }
        if (e.workers() != null && "DISTRIBUTED_CPU".equals(e.mode())) {
            return ", " + e.workers() + " workers";
        }
        return "";
    }

    static String ms(Double value) {
        if (value == null) {
            return "-";
        }
        return value < 1000 ? String.format(Locale.ROOT, "%.2f ms", value)
                : String.format(Locale.ROOT, "%.3f s", value / 1000);
    }

    static String bytes(Long value) {
        if (value == null) {
            return "-";
        }
        return value < 1024 ? value + " B" : value < 1024 * 1024 ? String.format(Locale.ROOT, "%.1f KiB", value / 1024.0)
                : String.format(Locale.ROOT, "%.1f MiB", value / 1048576.0);
    }

    private static String metric(double value, MetricStats m) {
        return switch (m.unit() == null ? "" : m.unit()) {
            case "ms" -> ms(value);
            case "bytes" -> bytes(Math.round(value));
            case "ratio" -> String.format(Locale.ROOT, "%.3f", value);
            case "count" -> value == Math.rint(value) ? String.valueOf((long) value)
                    : String.format(Locale.ROOT, "%.2f", value);
            default -> String.format(Locale.ROOT, "%.4g", value) + (m.unit() == null ? "" : " " + m.unit());
        };
    }

    private static String value(Object value) {
        if (value instanceof List<?> list) {
            return list.size() > 12 ? list.size() + " values" : list.toString();
        }
        return String.valueOf(value);
    }
}
