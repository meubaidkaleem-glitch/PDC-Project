package org.districompute.app.persistence;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Batches. */
public interface BatchRepository extends JpaRepository<Batch, String> {

    /**
     * Batches of a user.
     *
     * @param ownerId owner
     * @param pageable page and order
     * @return batches
     */
    List<Batch> findByOwnerId(Long ownerId, Pageable pageable);
}
