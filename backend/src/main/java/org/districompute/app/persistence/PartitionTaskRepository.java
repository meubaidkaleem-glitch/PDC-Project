package org.districompute.app.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Partition records. */
public interface PartitionTaskRepository extends JpaRepository<PartitionTask, Long> {

    /**
     * Partitions of a run.
     *
     * @param runId run
     * @return partitions by index
     */
    List<PartitionTask> findByRunIdOrderByPartitionIndexAsc(Long runId);

    /**
     * Partitions of an experiment.
     *
     * @param experimentId experiment
     * @return partitions
     */
    List<PartitionTask> findByExperimentIdOrderByRunIdAscPartitionIndexAsc(String experimentId);

    /**
     * Partitions executed by a worker.
     *
     * @param workerId worker
     * @return count
     */
    long countByWorkerId(String workerId);
}
