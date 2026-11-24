package org.districompute.app.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.districompute.app.persistence.Enums.RunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Runs that were executing at some time between two instants, of any experiment and user. Used to tell whether
     * measured runs competed with others.
     *
     * @param from start of the window
     * @param to end of the window
     * @return runs whose execution overlaps the window
     */
    @Query("select r from ExperimentRun r where r.startedAt < :to and r.completedAt > :from")
    List<ExperimentRun> findExecutingBetween(@Param("from") Instant from, @Param("to") Instant to);
}
