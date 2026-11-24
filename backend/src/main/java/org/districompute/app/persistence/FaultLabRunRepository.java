package org.districompute.app.persistence;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Fault lab runs. */
public interface FaultLabRunRepository extends JpaRepository<FaultLabRun, String> {

    /**
     * Recent lab runs, newest first.
     *
     * @param pageable limit
     * @return runs
     */
    List<FaultLabRun> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Lab runs in a status.
     *
     * @param status status
     * @return runs
     */
    List<FaultLabRun> findByStatus(FaultLabRun.Status status);
}
