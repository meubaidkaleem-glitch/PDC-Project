package org.districompute.app.settings;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.districompute.app.api.ApiException;
import org.districompute.app.config.AppProperties;
import org.districompute.app.persistence.AppSetting;
import org.districompute.app.persistence.AppSettingRepository;
import org.districompute.app.security.AuditService;
import org.districompute.distributed.coordinator.Coordinator;
import org.districompute.distributed.scheduler.PolicyName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settings administrators change at run time, stored in {@code app_settings} and applied again at startup, and a
 * read-only view of the server's configuration ({@code docs/configuration.md}). Secrets are never stored here or
 * shown.
 */
@Service
public class SettingsService {

    /** Key of the coordinator's default scheduling policy. */
    static final String POLICY = "coordinator.scheduling-policy";
    private static final Logger LOG = LoggerFactory.getLogger(SettingsService.class);

    private final AppSettingRepository settings;
    private final Coordinator coordinator;
    private final AppProperties properties;
    private final AuditService audit;
    private final Clock clock;

    SettingsService(AppSettingRepository settings, Coordinator coordinator, AppProperties properties,
            AuditService audit, Clock clock) {
        this.settings = settings;
        this.coordinator = coordinator;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
    }

    /** Applies the stored settings to the coordinator. */
    @EventListener(ApplicationReadyEvent.class)
    public void apply() {
        settings.findById(POLICY).ifPresent(s -> {
            try {
                coordinator.setSchedulingPolicy(PolicyName.valueOf(s.getSettingValue()));
            } catch (IllegalArgumentException e) {
                LOG.warn("Ignoring the stored scheduling policy {}: {}", s.getSettingValue(), e.getMessage());
            }
        });
    }

    /**
     * The settings and, for administrators, the server's configuration.
     *
     * @param admin whether the caller is an administrator
     * @return the view
     */
    @Transactional(readOnly = true)
    public Map<String, Object> view(boolean admin) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schedulingPolicy", coordinator.schedulingPolicy().name());
        out.put("availablePolicies", coordinator.availablePolicies().stream().map(PolicyName::name).toList());
        settings.findById(POLICY).ifPresent(s -> {
            out.put("schedulingPolicyChangedBy", s.getUpdatedBy());
            out.put("schedulingPolicyChangedAt", s.getUpdatedAt());
        });
        AppProperties.Limits l = properties.limits();
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("maxActiveExperimentsPerUser", l.maxActiveExperimentsPerUser());
        limits.put("submissionsPerMinute", l.submissionsPerMinute());
        limits.put("maxRepetitions", l.maxRepetitions());
        limits.put("maxBatchSize", l.maxBatchSize());
        limits.put("maxEstimatedOperations", l.maxEstimatedOperations());
        limits.put("maxEstimatedMemoryBytes", l.maxEstimatedMemoryBytes());
        limits.put("maxRunDurationSeconds", l.maxRunDuration().toSeconds());
        limits.put("maxUploadBytes", properties.storage().maxUploadBytes());
        limits.put("maxResultBytes", properties.storage().maxResultBytes());
        out.put("limits", limits);
        if (admin) {
            AppProperties.Coordinator c = properties.coordinator();
            Map<String, Object> server = new LinkedHashMap<>();
            server.put("workerPort", coordinator.port());
            server.put("bindAddress", c.bindAddress());
            server.put("advertisedHost", c.advertisedHost());
            server.put("tls", c.tlsKeystore() != null);
            server.put("heartbeat", c.heartbeatInterval().toMillis() + " ms; suspect after "
                    + c.suspectAfter().toMillis() + " ms; lost after " + c.heartbeatTimeout().toMillis() + " ms");
            server.put("maxWorkers", c.maxWorkers());
            server.put("workersRequireApproval", properties.workers().requireApproval());
            server.put("faultLabEnabled", properties.faults().enabled());
            server.put("sessionTimeoutMinutes", properties.security().sessionTimeout().toMinutes());
            server.put("dataDirectory", properties.dataDir().toAbsolutePath().normalize().toString());
            out.put("server", server);
        }
        return out;
    }

    /**
     * Changes the coordinator's default scheduling policy now and for later starts.
     *
     * @param policy policy name
     * @param username who changes it
     * @param admin whether the caller is an administrator
     * @return the new view
     */
    @Transactional
    public Map<String, Object> setSchedulingPolicy(String policy, String username, boolean admin) {
        PolicyName name;
        try {
            name = PolicyName.valueOf(policy);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.badRequest("UNKNOWN_POLICY", "Use one of " + coordinator.availablePolicies());
        }
        if (!coordinator.availablePolicies().contains(name)) {
            throw ApiException.badRequest("UNKNOWN_POLICY", "Use one of " + coordinator.availablePolicies());
        }
        String previous = coordinator.schedulingPolicy().name();
        coordinator.setSchedulingPolicy(name);
        AppSetting setting = settings.findById(POLICY).orElseGet(() -> new AppSetting(POLICY));
        setting.setSettingValue(name.name());
        setting.setUpdatedBy(username);
        setting.setUpdatedAt(clock.instant());
        settings.save(setting);
        audit.record(username, "SETTING_CHANGED", "SETTING", POLICY, previous + " -> " + name.name(), null);
        return view(admin);
    }
}
