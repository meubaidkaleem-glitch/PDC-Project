package org.districompute.app.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Application settings ({@code districompute.*}), documented in {@code docs/configuration.md}. Secrets are never
 * values here: they come from files or environment variables named here.
 *
 * @param dataDir directory for results, uploads and generated secrets
 * @param coordinator embedded Distributed coordinator
 * @param limits execution limits applied before anything is submitted
 * @param storage result and upload storage
 * @param security accounts and sessions
 * @param workers worker policy
 * @param faults fault-simulation lab
 */
@ConfigurationProperties("districompute")
public record AppProperties(
        @DefaultValue("./data") Path dataDir,
        @DefaultValue Coordinator coordinator,
        @DefaultValue Limits limits,
        @DefaultValue Storage storage,
        @DefaultValue Security security,
        @DefaultValue Workers workers,
        @DefaultValue Faults faults) {

    /**
     * Embedded coordinator.
     *
     * @param bindAddress address of the worker port
     * @param port worker port
     * @param advertisedHost host name workers use to reach the coordinator (shown in registration instructions)
     * @param secretFile file with the cluster's shared secret; if absent and {@code DISTRICOMPUTE_SECRET} is not set,
     *     a random secret is generated in {@code <dataDir>/cluster.secret}
     * @param tlsKeystore PKCS12 key store enabling TLS on the worker port, or empty
     * @param tlsKeystorePasswordFile file with the key store password
     * @param heartbeatInterval worker heartbeat interval
     * @param suspectAfter silence after which a worker is suspect
     * @param heartbeatTimeout silence after which a worker is disconnected
     * @param maxWorkers connected workers accepted
     */
    public record Coordinator(
            @DefaultValue("0.0.0.0") String bindAddress,
            @DefaultValue("7650") int port,
            @DefaultValue("localhost") String advertisedHost,
            Path secretFile,
            Path tlsKeystore,
            Path tlsKeystorePasswordFile,
            @DefaultValue("2s") Duration heartbeatInterval,
            @DefaultValue("5s") Duration suspectAfter,
            @DefaultValue("10s") Duration heartbeatTimeout,
            @DefaultValue("256") int maxWorkers) {
    }

    /**
     * Limits checked before submission (Core and Distributed apply their own limits as well).
     *
     * @param maxActiveExperimentsPerUser experiments a user may have queued or running
     * @param submissionsPerMinute experiments a user may create per minute
     * @param maxRepetitions repetitions per experiment
     * @param maxBatchSize experiments per batch
     * @param maxEstimatedOperations largest Core operation estimate accepted
     * @param maxEstimatedMemoryBytes largest Core peak-memory estimate accepted
     * @param maxThreads largest thread count for multithreaded runs (0: available processors)
     * @param maxRunDuration deadline of one run (Core {@code timeoutMillis})
     */
    public record Limits(
            @DefaultValue("5") int maxActiveExperimentsPerUser,
            @DefaultValue("30") int submissionsPerMinute,
            @DefaultValue("20") int maxRepetitions,
            @DefaultValue("50") int maxBatchSize,
            @DefaultValue("1e13") double maxEstimatedOperations,
            @DefaultValue("2147483648") long maxEstimatedMemoryBytes,
            @DefaultValue("0") int maxThreads,
            @DefaultValue("1h") Duration maxRunDuration) {
    }

    /**
     * Result and upload storage.
     *
     * @param maxResultBytes largest result value stored as a file (larger ones keep only Core's summary)
     * @param maxUploadBytes largest uploaded input file
     * @param previewRows rows in a result preview
     * @param previewColumns columns in a result preview
     */
    public record Storage(
            @DefaultValue("536870912") long maxResultBytes,
            @DefaultValue("67108864") long maxUploadBytes,
            @DefaultValue("20") int previewRows,
            @DefaultValue("12") int previewColumns) {
    }

    /**
     * Accounts.
     *
     * @param adminUsername name of the administrator created on first start
     * @param adminPasswordFile file with that administrator's initial password; if absent and
     *     {@code DISTRICOMPUTE_ADMIN_PASSWORD} is not set, a random password is written to
     *     {@code <dataDir>/initial-admin-password}
     * @param sessionTimeout idle session timeout
     */
    public record Security(
            @DefaultValue("admin") String adminUsername,
            Path adminPasswordFile,
            @DefaultValue("8h") Duration sessionTimeout) {
    }

    /**
     * Worker policy.
     *
     * @param requireApproval if {@code true}, workers not approved by an administrator are deactivated when they
     *     register
     */
    public record Workers(@DefaultValue("false") boolean requireApproval) {
    }

    /**
     * Fault-simulation lab ({@code docs/fault-lab.md}).
     *
     * @param enabled whether the lab is available (test and demonstration environments only)
     */
    public record Faults(@DefaultValue("false") boolean enabled) {
    }
}
