package org.districompute.app.security;

import java.time.Clock;
import org.districompute.app.persistence.AuditEvent;
import org.districompute.app.persistence.AuditEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Records security-relevant actions (logins, submissions, worker and settings changes) in {@code audit_events}. */
@Service
public class AuditService {

    private static final Logger LOG = LoggerFactory.getLogger(AuditService.class);

    private final AuditEventRepository events;
    private final Clock clock;

    AuditService(AuditEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    /**
     * Records an action. Failures are logged and never break the action itself.
     *
     * @param username acting user, or {@code null}
     * @param action action name
     * @param targetType target type, or {@code null}
     * @param targetId target id, or {@code null}
     * @param details details, or {@code null}
     * @param remoteAddress client address, or {@code null}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String username, String action, String targetType, String targetId, String details,
            String remoteAddress) {
        try {
            events.save(new AuditEvent(username == null ? null : truncate(username, 64), action, targetType,
                    targetId, details, remoteAddress, clock.instant()));
        } catch (RuntimeException e) {
            LOG.warn("Could not record audit event {} for {}", action, username, e);
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
