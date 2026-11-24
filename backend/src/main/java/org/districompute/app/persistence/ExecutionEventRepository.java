package org.districompute.app.persistence;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Execution events. */
public interface ExecutionEventRepository extends JpaRepository<ExecutionEvent, Long> {

    /**
     * Events of an experiment after an id (for replay).
     *
     * @param experimentId experiment
     * @param afterId exclusive lower bound
     * @param pageable limit
     * @return events, oldest first
     */
    List<ExecutionEvent> findByExperimentIdAndIdGreaterThanOrderByIdAsc(String experimentId, long afterId,
            Pageable pageable);

    /**
     * Events of a worker of the application's coordinator, newest first (fault lab events excluded).
     *
     * @param workerId worker
     * @param pageable limit
     * @return events
     */
    List<ExecutionEvent> findByWorkerIdAndSimulatedFalseOrderByIdDesc(String workerId, Pageable pageable);

    /**
     * Recent events of the application's coordinator and experiments, newest first (fault lab events excluded).
     *
     * @param pageable limit
     * @return events
     */
    List<ExecutionEvent> findBySimulatedFalseOrderByIdDesc(Pageable pageable);

    /**
     * Events of a fault lab run, oldest first.
     *
     * @param labRunId lab run
     * @param pageable limit
     * @return events
     */
    List<ExecutionEvent> findByLabRunIdOrderByIdAsc(String labRunId, Pageable pageable);

    /**
     * Whether a runtime event was already stored (duplicate delivery).
     *
     * @param runtimeSequence runtime sequence
     * @param simulated whether the event came from the fault lab
     * @return {@code true} if stored
     */
    boolean existsByRuntimeSequenceAndSimulated(Long runtimeSequence, boolean simulated);
}
