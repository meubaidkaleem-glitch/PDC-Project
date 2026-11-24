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

    /**
     * Whether the owner already has a template with this name.
     *
     * @param ownerId owner
     * @param name name
     * @return whether it exists
     */
    boolean existsByOwnerIdAndName(Long ownerId, String name);

    /**
     * How many templates the owner has.
     *
     * @param ownerId owner
     * @return the count
     */
    long countByOwnerId(Long ownerId);
}
