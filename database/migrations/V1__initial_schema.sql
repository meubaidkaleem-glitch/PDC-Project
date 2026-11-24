-- DistriCompute application schema, version 1 (docs/database.md).
-- MySQL 8.4, InnoDB, utf8mb4. Large data (inputs, results) lives in files; tables hold metadata and references.
-- Times are DATETIME(3) in UTC (MySQL TIMESTAMP ends in 2038).

CREATE TABLE app_users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt hash, never the password',
    role          VARCHAR(16)  NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_app_users_username UNIQUE (username),
    CONSTRAINT ck_app_users_role CHECK (role IN ('ADMIN', 'USER'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE batches (
    id         CHAR(36)     NOT NULL,
    owner_id   BIGINT       NOT NULL,
    name       VARCHAR(200) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_batches_owner FOREIGN KEY (owner_id) REFERENCES app_users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE experiments (
    id                      CHAR(36)      NOT NULL COMMENT 'UUID; also Core experimentId',
    name                    VARCHAR(200)  NOT NULL,
    description             TEXT          NULL,
    owner_id                BIGINT        NOT NULL,
    batch_id                CHAR(36)      NULL,
    category                VARCHAR(32)   NOT NULL,
    algorithm_id            VARCHAR(64)   NOT NULL,
    algorithm_version       VARCHAR(32)   NOT NULL,
    parameters              JSON          NOT NULL COMMENT 'Core parameters without inline arrays',
    input_source            VARCHAR(16)   NOT NULL,
    input_ref               VARCHAR(255)  NULL COMMENT 'stored input file (uploads, large inline inputs)',
    input_summary           JSON          NULL COMMENT 'dimensions, sizes, file name and bytes',
    execution_mode          VARCHAR(24)   NOT NULL,
    recommended_mode        VARCHAR(24)   NULL,
    recommendation_accepted BOOLEAN       NULL,
    override_reason         VARCHAR(500)  NULL,
    advice_basis            VARCHAR(24)   NULL,
    scheduling_policy       VARCHAR(24)   NULL,
    configuration           JSON          NOT NULL COMMENT 'threads, partitions, limits, repetitions',
    repetitions             INT           NOT NULL DEFAULT 1,
    status                  VARCHAR(16)   NOT NULL,
    created_at              DATETIME(3)   NOT NULL,
    started_at              DATETIME(3)   NULL,
    completed_at            DATETIME(3)   NULL,
    error_code              VARCHAR(48)   NULL,
    error_message           VARCHAR(2000) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_experiments_owner FOREIGN KEY (owner_id) REFERENCES app_users (id),
    CONSTRAINT fk_experiments_batch FOREIGN KEY (batch_id) REFERENCES batches (id) ON DELETE SET NULL,
    CONSTRAINT ck_experiments_status CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_experiments_input CHECK (input_source IN ('GENERATED', 'INLINE', 'UPLOAD')),
    CONSTRAINT ck_experiments_repetitions CHECK (repetitions BETWEEN 1 AND 100),
    INDEX ix_experiments_owner_created (owner_id, created_at),
    INDEX ix_experiments_status (status),
    INDEX ix_experiments_algorithm (algorithm_id, created_at),
    INDEX ix_experiments_batch (batch_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE experiment_runs (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    experiment_id       CHAR(36)      NOT NULL,
    run_index           INT           NOT NULL,
    core_task_id        VARCHAR(128)  NOT NULL COMMENT 'Core taskId: <experimentId>-r<index>',
    execution_mode      VARCHAR(24)   NOT NULL,
    status              VARCHAR(16)   NOT NULL,
    threads             INT           NULL,
    partitions          INT           NULL,
    workers_used        INT           NULL,
    submitted_at        DATETIME(3)   NULL,
    started_at          DATETIME(3)   NULL,
    completed_at        DATETIME(3)   NULL,
    wall_time_ms        DOUBLE        NULL COMMENT 'submission to completion, measured by the application',
    result_digest       VARCHAR(80)   NULL,
    verification_passed BOOLEAN       NULL,
    verification_method VARCHAR(500)  NULL,
    error_code          VARCHAR(48)   NULL,
    error_message       VARCHAR(2000) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_runs_experiment FOREIGN KEY (experiment_id) REFERENCES experiments (id) ON DELETE CASCADE,
    CONSTRAINT uk_runs_index UNIQUE (experiment_id, run_index),
    CONSTRAINT uk_runs_core_task UNIQUE (core_task_id),
    CONSTRAINT ck_runs_status CHECK (status IN ('PENDING', 'QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- One row per partition of a run (the specification's "tasks" entity).
CREATE TABLE tasks (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    experiment_id   CHAR(36)      NOT NULL,
    run_id          BIGINT        NOT NULL,
    partition_index INT           NOT NULL,
    partition_id    VARCHAR(160)  NOT NULL,
    worker_id       VARCHAR(64)   NULL COMMENT 'worker of the attempt that delivered (or the last attempt)',
    status          VARCHAR(16)   NOT NULL,
    attempt_count   INT           NOT NULL DEFAULT 0,
    assigned_at     DATETIME(3)   NULL,
    started_at      DATETIME(3)   NULL,
    completed_at    DATETIME(3)   NULL,
    error_message   VARCHAR(2000) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_tasks_experiment FOREIGN KEY (experiment_id) REFERENCES experiments (id) ON DELETE CASCADE,
    CONSTRAINT fk_tasks_run FOREIGN KEY (run_id) REFERENCES experiment_runs (id) ON DELETE CASCADE,
    CONSTRAINT uk_tasks_partition UNIQUE (run_id, partition_index),
    INDEX ix_tasks_worker (worker_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE workers (
    worker_id         VARCHAR(64)   NOT NULL,
    hostname          VARCHAR(255)  NULL,
    endpoint          VARCHAR(255)  NULL,
    capabilities      JSON          NULL COMMENT 'as reported by the worker at registration',
    connection_status VARCHAR(16)   NOT NULL,
    status_reason     VARCHAR(500)  NULL,
    last_heartbeat_at DATETIME(3)   NULL,
    registered_at     DATETIME(3)   NULL COMMENT 'first registration with this application',
    last_seen_at      DATETIME(3)   NULL,
    approved          BOOLEAN       NOT NULL DEFAULT FALSE,
    approved_by       VARCHAR(64)   NULL,
    approved_at       DATETIME(3)   NULL,
    deactivated       BOOLEAN       NOT NULL DEFAULT FALSE,
    notes             VARCHAR(500)  NULL,
    completed_tasks   BIGINT        NOT NULL DEFAULT 0,
    failed_tasks      BIGINT        NOT NULL DEFAULT 0,
    created_at        DATETIME(3)   NOT NULL,
    updated_at        DATETIME(3)   NOT NULL,
    PRIMARY KEY (worker_id),
    INDEX ix_workers_status (connection_status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE run_metrics (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    experiment_id CHAR(36)     NOT NULL,
    run_id        BIGINT       NOT NULL,
    metric_name   VARCHAR(64)  NOT NULL,
    metric_value  DOUBLE       NOT NULL,
    unit          VARCHAR(24)  NOT NULL,
    source        VARCHAR(24)  NOT NULL COMMENT 'APPLICATION, CORE, RUNTIME or DERIVED',
    recorded_at   DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_metrics_experiment FOREIGN KEY (experiment_id) REFERENCES experiments (id) ON DELETE CASCADE,
    CONSTRAINT fk_metrics_run FOREIGN KEY (run_id) REFERENCES experiment_runs (id) ON DELETE CASCADE,
    CONSTRAINT uk_metrics_run_name UNIQUE (run_id, metric_name, source),
    CONSTRAINT ck_metrics_source CHECK (source IN ('APPLICATION', 'CORE', 'RUNTIME', 'DERIVED')),
    INDEX ix_metrics_experiment_name (experiment_id, metric_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE experiment_results (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    experiment_id       CHAR(36)      NOT NULL,
    run_id              BIGINT        NOT NULL,
    result_type         VARCHAR(32)   NOT NULL,
    summary             JSON          NULL COMMENT 'Core result summary values',
    digest              VARCHAR(80)   NOT NULL,
    validation_status   VARCHAR(24)   NOT NULL,
    validation_method   VARCHAR(500)  NULL,
    validation_message  VARCHAR(2000) NULL,
    storage_ref         VARCHAR(255)  NULL COMMENT 'path relative to the result store; NULL if not stored',
    storage_format      VARCHAR(8)    NULL,
    size_bytes          BIGINT        NULL,
    stored_digest_match BOOLEAN       NULL COMMENT 'digest of the stored value equals Core''s verified digest',
    storage_note        VARCHAR(500)  NULL,
    created_at          DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_results_experiment FOREIGN KEY (experiment_id) REFERENCES experiments (id) ON DELETE CASCADE,
    CONSTRAINT fk_results_run FOREIGN KEY (run_id) REFERENCES experiment_runs (id) ON DELETE CASCADE,
    CONSTRAINT uk_results_run UNIQUE (run_id),
    CONSTRAINT ck_results_validation CHECK (validation_status IN ('VERIFIED', 'VERIFICATION_FAILED', 'NOT_VERIFIED')),
    CONSTRAINT ck_results_format CHECK (storage_format IS NULL OR storage_format IN ('CSV', 'JSON'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE execution_events (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    experiment_id    CHAR(36)      NULL,
    run_id           BIGINT        NULL,
    task_ref         VARCHAR(160)  NULL COMMENT 'partition or attempt id',
    worker_id        VARCHAR(64)   NULL,
    runtime_sequence BIGINT        NULL COMMENT 'sequence number of the Distributed runtime event',
    event_type       VARCHAR(40)   NOT NULL,
    severity         VARCHAR(8)    NOT NULL,
    message          VARCHAR(1000) NOT NULL,
    details          JSON          NULL,
    simulated        BOOLEAN       NOT NULL DEFAULT FALSE,
    occurred_at      DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_events_experiment FOREIGN KEY (experiment_id) REFERENCES experiments (id) ON DELETE CASCADE,
    CONSTRAINT ck_events_severity CHECK (severity IN ('INFO', 'WARN', 'ERROR')),
    INDEX ix_events_experiment (experiment_id, id),
    INDEX ix_events_worker (worker_id, id),
    INDEX ix_events_time (occurred_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE experiment_templates (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    owner_id       BIGINT       NOT NULL,
    name           VARCHAR(200) NOT NULL,
    description    TEXT         NULL,
    algorithm_id   VARCHAR(64)  NOT NULL,
    execution_mode VARCHAR(24)  NOT NULL,
    parameters     JSON         NOT NULL,
    configuration  JSON         NOT NULL,
    created_at     DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_templates_owner FOREIGN KEY (owner_id) REFERENCES app_users (id),
    CONSTRAINT uk_templates_owner_name UNIQUE (owner_id, name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE app_settings (
    setting_key   VARCHAR(64)   NOT NULL,
    setting_value VARCHAR(1000) NOT NULL,
    updated_by    VARCHAR(64)   NULL,
    updated_at    DATETIME(3)   NOT NULL,
    PRIMARY KEY (setting_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE audit_events (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    username       VARCHAR(64)   NULL,
    action         VARCHAR(48)   NOT NULL,
    target_type    VARCHAR(32)   NULL,
    target_id      VARCHAR(128)  NULL,
    details        VARCHAR(2000) NULL,
    remote_address VARCHAR(64)   NULL,
    occurred_at    DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    INDEX ix_audit_time (occurred_at),
    INDEX ix_audit_user (username, occurred_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
