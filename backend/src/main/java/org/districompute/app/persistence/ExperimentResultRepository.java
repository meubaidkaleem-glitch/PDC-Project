package org.districompute.app.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Run results. */
public interface ExperimentResultRepository extends JpaRepository<ExperimentResult, Long> {

    /**
     * Results of an experiment.
     *
     * @param experimentId experiment
     * @return results
     */
    List<ExperimentResult> findByExperimentIdOrderByRunIdAsc(String experimentId);

    /**
     * The result of a run.
     *
     * @param runId run
     * @return the result
     */
    Optional<ExperimentResult> findByRunId(Long runId);
}
