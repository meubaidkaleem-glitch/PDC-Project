package org.districompute.core.metrics;

import java.util.Locale;

/** Renders {@link BenchmarkReport}s as CSV (for spreadsheets and plotting) and Markdown (for humans). */
public final class BenchmarkReports {

    private BenchmarkReports() {
    }

    /**
     * Renders one CSV row per case. Times are in milliseconds.
     *
     * @param report the report
     * @return CSV text with a header row
     */
    public static String toCsv(BenchmarkReport report) {
        StringBuilder csv = new StringBuilder("label,parameterSet,algorithm,version,mode,threads,partitions,runs,"
                + "median_ms,min_ms,max_ms,mean_ms,stddev_ms,compute_median_ms,partitioning_median_ms,"
                + "aggregation_median_ms,scheduling_overhead_median_ms,cpu_utilization_median,"
                + "allocated_bytes_median,gc_count,throughput_ops_per_s,speedup,efficiency,distinct_digests\n");
        for (BenchmarkReport.CaseResult c : report.cases()) {
            csv.append(quote(c.label())).append(',').append(c.parameterSet()).append(',')
                    .append(c.algorithm().id()).append(',').append(c.algorithm().version()).append(',')
                    .append(c.mode()).append(',').append(c.threads()).append(',').append(c.partitions()).append(',')
                    .append(c.execution().count()).append(',')
                    .append(ms(c.execution().median())).append(',').append(ms(c.execution().min())).append(',')
                    .append(ms(c.execution().max())).append(',').append(ms(c.execution().mean())).append(',')
                    .append(ms(c.execution().standardDeviation())).append(',').append(ms(c.compute().median()))
                    .append(',').append(ms(c.partitioningTime().median())).append(',')
                    .append(ms(c.aggregation().median())).append(',').append(ms(c.schedulingOverhead().median()))
                    .append(',').append(c.cpuUtilization() == null ? "" : fmt(c.cpuUtilization().median()))
                    .append(',').append(c.allocatedBytes() == null ? "" : fmt(c.allocatedBytes().median()))
                    .append(',').append(c.gcCount()).append(',').append(fmt(c.throughputPerSecond())).append(',')
                    .append(c.speedup() == null ? "" : fmt(c.speedup())).append(',')
                    .append(c.efficiency() == null ? "" : fmt(c.efficiency())).append(',')
                    .append(c.resultDigests().size()).append('\n');
        }
        return csv.toString();
    }

    /**
     * Renders a Markdown report with the environment, the methodology summary and one table row per case.
     *
     * @param report the report
     * @return Markdown text
     */
    public static String toMarkdown(BenchmarkReport report) {
        EnvironmentInfo env = report.environment();
        StringBuilder md = new StringBuilder();
        md.append("# Benchmark report: ").append(report.suite()).append("\n\n");
        if (!report.description().isBlank()) {
            md.append(report.description()).append("\n\n");
        }
        md.append("| Environment | |\n|---|---|\n")
                .append("| Measured | ").append(report.startedAt()).append(" to ").append(report.finishedAt())
                .append(" |\n")
                .append("| CPU | ").append(env.cpuModel()).append(" (").append(env.availableProcessors())
                .append(" processors available to the JVM) |\n")
                .append("| Memory | max heap ").append(mib(env.maxHeapBytes())).append(" MiB, physical ")
                .append(env.totalPhysicalMemoryBytes() < 0 ? "unknown" : mib(env.totalPhysicalMemoryBytes()) + " MiB")
                .append(" |\n")
                .append("| JVM | ").append(env.javaVendor()).append(' ').append(env.javaVersion()).append(" (")
                .append(env.jvmName()).append(") |\n")
                .append("| OS | ").append(env.osName()).append(' ').append(env.osVersion()).append(' ')
                .append(env.osArch()).append(" |\n")
                .append("| DistriCompute-Core | ").append(env.coreVersion()).append(" |\n")
                .append("| Engine worker threads | ").append(report.engineThreads()).append(" |\n")
                .append("| Runs per case | ").append(report.warmupRuns()).append(" warm-up + ")
                .append(report.measuredRuns()).append(" measured |\n\n");
        md.append("Times are medians of `executionNanos` (partitioning + compute + aggregation) in milliseconds. "
                + "Input generation and verification are excluded. Speedup = median sequential time / median "
                + "time for the same parameter set. Efficiency = speedup / threads. Every measured run passed "
                + "independent result verification.\n\n");
        md.append("| Case | Partitions | Median ms | Min ms | Max ms | SD ms | Partitioning ms | Aggregation ms "
                + "| Sched. overhead ms | CPU util. | Speedup | Efficiency |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (BenchmarkReport.CaseResult c : report.cases()) {
            md.append("| ").append(c.label()).append(" | ").append(c.partitions()).append(" | ")
                    .append(ms(c.execution().median())).append(" | ").append(ms(c.execution().min())).append(" | ")
                    .append(ms(c.execution().max())).append(" | ").append(ms(c.execution().standardDeviation()))
                    .append(" | ").append(ms(c.partitioningTime().median())).append(" | ")
                    .append(ms(c.aggregation().median())).append(" | ").append(ms(c.schedulingOverhead().median()))
                    .append(" | ").append(c.cpuUtilization() == null ? "n/a" : fmt(c.cpuUtilization().median()))
                    .append(" | ").append(c.speedup() == null ? "baseline" : fmt(c.speedup()))
                    .append(" | ").append(c.efficiency() == null ? "" : fmt(c.efficiency())).append(" |\n");
        }
        md.append("\nParameter sets:\n\n");
        report.cases().stream().filter(c -> c.mode() == org.districompute.core.execution.ExecutionMode.SEQUENTIAL_CPU
                || report.cases().stream().noneMatch(o -> o.parameterSet() == c.parameterSet()
                        && o.mode() == org.districompute.core.execution.ExecutionMode.SEQUENTIAL_CPU))
                .map(c -> "* set " + c.parameterSet() + ": `" + c.algorithm() + "` " + c.parameters() + "\n")
                .distinct().forEach(md::append);
        return md.toString();
    }

    private static String ms(double nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1e6);
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.4g", value);
    }

    private static long mib(long bytes) {
        return bytes / (1024 * 1024);
    }

    private static String quote(String text) {
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
