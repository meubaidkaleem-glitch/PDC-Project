package org.districompute.app.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** Worker records. */
public interface WorkerRepository extends JpaRepository<WorkerEntity, String> {
}
