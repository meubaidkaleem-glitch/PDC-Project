package org.districompute.app.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.districompute.app.persistence.Enums.RunStatus;
import org.springframework.data.jpa.repository.JpaRepository;

/** Experiment runs. */
public interface ExperimentRunRepository extends JpaRepository<ExperimentRun, Long> {

    /**
     * Runs of an experiment.
     *
     * @param experimentId experiment
     * @return runs by index
     */
    List<ExperimentRun> findByExperimentIdOrderByRunIndexAsc(String experimentId);

    /**
     * Runs of several experiments.
     *
     * @param experimentIds experiments
     * @return runs
     */
    List<ExperimentRun> findByExperimentIdIn(Collection<String> experimentIds);

    /**
     * The run of a Core task.
     *
     * @param coreTaskId Core task id
     * @return the run
     */
    Optional<ExperimentRun> findByCoreTaskId(String coreTaskId);

    /**
     * Completed runs of an algorithm in a mode, for history-based estimates.
     *
     * @param experimentIds experiments of the algorithm
     * @param status status
     * @return runs
     */
    List<ExperimentRun> findByExperimentIdInAndStatus(Collection<String> experimentIds, RunStatus status);
}
