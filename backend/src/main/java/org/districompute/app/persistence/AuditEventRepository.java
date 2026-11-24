package org.districompute.app.persistence;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Audit records. */
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    /**
     * Recent audit records.
     *
     * @param pageable limit
     * @return records, newest first
     */
    List<AuditEvent> findAllByOrderByIdDesc(Pageable pageable);
}
