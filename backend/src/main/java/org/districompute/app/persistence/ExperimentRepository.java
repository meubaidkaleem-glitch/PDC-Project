package org.districompute.app.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.districompute.app.persistence.Enums.ExperimentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Experiments. Filtering for the history page uses specifications ({@code ExperimentQueries}). */
public interface ExperimentRepository extends JpaRepository<Experiment, String>, JpaSpecificationExecutor<Experiment> {

    /**
     * Experiments in some statuses.
     *
     * @param statuses statuses
     * @return experiments, oldest first
     */
    List<Experiment> findByStatusInOrderByCreatedAtAsc(Collection<ExperimentStatus> statuses);

    /**
     * Experiments of a batch.
     *
     * @param batchId batch
     * @return experiments, oldest first
     */
    List<Experiment> findByBatchIdOrderByCreatedAtAsc(String batchId);

    /**
     * Number of experiments of an owner in some statuses.
     *
     * @param ownerId owner
     * @param statuses statuses
     * @return count
     */
    long countByOwnerIdAndStatusIn(Long ownerId, Collection<ExperimentStatus> statuses);

    /**
     * Counts experiments per status, for everyone or for one owner.
     *
     * @param ownerId owner, or {@code null} for all
     * @return rows of (status, count)
     */
    @Query("select e.status, count(e) from Experiment e where (:ownerId is null or e.owner.id = :ownerId) "
            + "group by e.status")
    List<Object[]> countByStatus(@Param("ownerId") Long ownerId);

    /**
     * Recent experiments, for everyone or for one owner.
     *
     * @param ownerId owner, or {@code null} for all
     * @param since lower bound of the creation time
     * @param pageable page
     * @return experiments, newest first
     */
    @Query("select e from Experiment e where (:ownerId is null or e.owner.id = :ownerId) and e.createdAt >= :since "
            + "order by e.createdAt desc")
    Page<Experiment> recent(@Param("ownerId") Long ownerId, @Param("since") Instant since, Pageable pageable);
}
