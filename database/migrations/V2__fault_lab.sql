-- Fault lab (docs/fault-lab.md): runs of predefined fault scenarios on an isolated coordinator with simulated
-- workers. Their runtime events are stored in execution_events with simulated = TRUE and the lab run's id, never
-- with an experiment.

CREATE TABLE fault_lab_runs (
    id             CHAR(36)      NOT NULL,
    owner_id       BIGINT        NOT NULL,
    scenario       VARCHAR(40)   NOT NULL,
    status         VARCHAR(16)   NOT NULL,
    seed           BIGINT        NOT NULL,
    algorithm_id   VARCHAR(100)  NOT NULL,
    parameters     JSON          NOT NULL,
    fault_profile  JSON          NOT NULL,
    outcome        JSON          NULL,
    error_message  VARCHAR(2000) NULL,
    created_at     DATETIME(3)   NOT NULL,
    completed_at   DATETIME(3)   NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_lab_runs_owner FOREIGN KEY (owner_id) REFERENCES app_users (id),
    CONSTRAINT ck_lab_runs_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    INDEX ix_lab_runs_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

ALTER TABLE execution_events
    ADD COLUMN lab_run_id CHAR(36) NULL AFTER run_id,
    ADD CONSTRAINT fk_events_lab_run FOREIGN KEY (lab_run_id) REFERENCES fault_lab_runs (id) ON DELETE CASCADE,
    ADD CONSTRAINT ck_events_lab_simulated CHECK (lab_run_id IS NULL OR simulated),
    ADD INDEX ix_events_lab_run (lab_run_id, id);
