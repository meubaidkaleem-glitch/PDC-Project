package org.districompute.app.persistence;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Run metrics. */
public interface RunMetricRepository extends JpaRepository<RunMetric, Long> {

    /**
     * Metrics of an experiment.
     *
     * @param experimentId experiment
     * @return metrics
     */
    List<RunMetric> findByExperimentIdOrderByRunIdAscMetricNameAsc(String experimentId);

    /**
     * Metrics of several experiments.
     *
     * @param experimentIds experiments
     * @return metrics
     */
    List<RunMetric> findByExperimentIdIn(Collection<String> experimentIds);

    /**
     * One metric across runs, for history-based estimates.
     *
     * @param runIds runs
     * @param metricName name
     * @return metrics
     */
    List<RunMetric> findByRunIdInAndMetricName(Collection<Long> runIds, String metricName);
}
