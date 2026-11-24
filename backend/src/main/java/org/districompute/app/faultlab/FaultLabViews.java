package org.districompute.app.faultlab;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** API records of the fault lab ({@code docs/fault-lab.md}). */
public final class FaultLabViews {

    private FaultLabViews() {
    }

    /**
     * A scenario.
     *
     * @param id identifier
     * @param title short name
     * @param fault the injected fault
     * @param expectedDetection how the runtime is designed to detect it
     * @param expectedRecovery how the runtime is designed to recover
     * @param attemptTimeoutSeconds attempt timeout used in the lab
     * @param maxAttempts attempts per partition
     */
    public record ScenarioView(String id, String title, String fault, String expectedDetection,
            String expectedRecovery, long attemptTimeoutSeconds, int maxAttempts) {
    }

    /**
     * Whether the lab can run, and its scenarios.
     *
     * @param enabled whether {@code districompute.faults.enabled} is set
     * @param busy whether a scenario is running
     * @param runningId the running lab run, or {@code null}
     * @param scenarios the scenarios
     * @param setup what a run consists of
     */
    public record LabStatus(boolean enabled, boolean busy, String runningId, List<ScenarioView> scenarios,
            Map<String, Object> setup) {
    }

    /**
     * A request to run a scenario.
     *
     * @param scenario scenario id
     * @param seed seed of the fault profile and the generated input; random if absent
     */
    public record StartLabRun(@NotBlank String scenario, @Min(0) Long seed) {
    }

    /**
     * A lab run.
     *
     * @param id identifier
     * @param scenario scenario id
     * @param title scenario name
     * @param owner who started it
     * @param status RUNNING, COMPLETED (the scenario ran; see the outcome) or FAILED (the lab could not run it)
     * @param simulated always true: everything in a lab run is simulated
     * @param seed seed
     * @param algorithm algorithm
     * @param parameters algorithm parameters
     * @param faultProfile the faulty worker's profile
     * @param outcome what happened: detection, recovery, result, attempts; {@code null} while running
     * @param errorMessage why the lab could not run it
     * @param createdAt start
     * @param completedAt end
     */
    public record LabRunView(String id, String scenario, String title, String owner, String status,
            boolean simulated, long seed, String algorithm, Map<String, Object> parameters,
            Map<String, Object> faultProfile, Map<String, Object> outcome, String errorMessage, Instant createdAt,
            Instant completedAt) {
    }
}
