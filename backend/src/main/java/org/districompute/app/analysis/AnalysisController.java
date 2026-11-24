package org.districompute.app.analysis;

import jakarta.validation.Valid;
import java.util.List;
import org.districompute.app.analysis.AnalysisViews.CompareRequest;
import org.districompute.app.analysis.AnalysisViews.Comparison;
import org.districompute.app.analysis.AnalysisViews.WorkloadGroup;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Comparison and performance analysis of completed experiments ({@code docs/analysis.md}). */
@RestController
@RequestMapping("/api/v1/analysis")
public class AnalysisController {

    private final AnalysisService analysis;

    AnalysisController(AnalysisService analysis) {
        this.analysis = analysis;
    }

    /**
     * Compares experiments: compatibility, statistics, speedup and efficiency against a baseline.
     *
     * @param request experiments and optional baseline
     * @return the comparison
     */
    @PostMapping("/compare")
    public Comparison compare(@Valid @RequestBody CompareRequest request) {
        return analysis.compare(request);
    }

    /**
     * Completed experiments grouped by workload, so that a group can be compared at once.
     *
     * @return groups, newest first
     */
    @GetMapping("/workloads")
    public List<WorkloadGroup> workloads() {
        return analysis.workloads();
    }
}
