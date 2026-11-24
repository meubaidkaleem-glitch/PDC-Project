package org.districompute.app.faultlab;

import jakarta.validation.Valid;
import java.util.List;
import org.districompute.app.faultlab.FaultLabViews.LabRunView;
import org.districompute.app.faultlab.FaultLabViews.LabStatus;
import org.districompute.app.faultlab.FaultLabViews.StartLabRun;
import org.districompute.app.monitoring.EventView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The fault lab, for administrators only ({@code docs/fault-lab.md}). */
@RestController
@RequestMapping("/api/v1/fault-lab")
@PreAuthorize("hasRole('ADMIN')")
public class FaultLabController {

    private final FaultLabService lab;

    FaultLabController(FaultLabService lab) {
        this.lab = lab;
    }

    /** @return whether the lab is enabled and busy, and its scenarios */
    @GetMapping
    public LabStatus status() {
        return lab.status();
    }

    /**
     * Runs a scenario in the background.
     *
     * @param request scenario and seed
     * @return 202 with the lab run
     */
    @PostMapping("/runs")
    public ResponseEntity<LabRunView> start(@Valid @RequestBody StartLabRun request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(lab.start(request));
    }

    /**
     * Recent lab runs.
     *
     * @param limit how many
     * @return runs, newest first
     */
    @GetMapping("/runs")
    public List<LabRunView> list(@RequestParam(defaultValue = "20") int limit) {
        return lab.list(limit);
    }

    /**
     * One lab run.
     *
     * @param id lab run
     * @return the run
     */
    @GetMapping("/runs/{id}")
    public LabRunView get(@PathVariable String id) {
        return lab.get(id);
    }

    /**
     * Events of a lab run, all simulated.
     *
     * @param id lab run
     * @return events, oldest first
     */
    @GetMapping("/runs/{id}/events")
    public List<EventView> events(@PathVariable String id) {
        return lab.events(id);
    }
}
