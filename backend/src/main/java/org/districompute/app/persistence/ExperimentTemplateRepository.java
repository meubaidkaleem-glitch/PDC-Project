package org.districompute.app.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Templates. */
public interface ExperimentTemplateRepository extends JpaRepository<ExperimentTemplate, Long> {

    /**
     * Templates of an owner.
     *
     * @param ownerId owner
     * @return templates by name
     */
    List<ExperimentTemplate> findByOwnerIdOrderByNameAsc(Long ownerId);
}
