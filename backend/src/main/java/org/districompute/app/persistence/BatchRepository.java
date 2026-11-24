package org.districompute.app.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** Batches. */
public interface BatchRepository extends JpaRepository<Batch, String> {
}
