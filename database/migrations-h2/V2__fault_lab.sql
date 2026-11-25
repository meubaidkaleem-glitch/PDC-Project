-- Fault lab (docs/fault-lab.md), H2 version of ../migrations/V2__fault_lab.sql.

CREATE TABLE fault_lab_runs (
    id             VARCHAR(36)      NOT NULL,
    owner_id       BIGINT        NOT NULL,
    scenario       VARCHAR(40)   NOT NULL,
    status         VARCHAR(16)   NOT NULL,
    seed           BIGINT        NOT NULL,
    algorithm_id   VARCHAR(100)  NOT NULL,
    parameters     JSON          NOT NULL,
    fault_profile  JSON          NOT NULL,
    outcome        JSON          NULL,
    error_message  VARCHAR(2000) NULL,
    created_at     TIMESTAMP(3)  NOT NULL,
    completed_at   TIMESTAMP(3)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_lab_runs_owner FOREIGN KEY (owner_id) REFERENCES app_users (id),
    CONSTRAINT ck_lab_runs_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED'))
);
CREATE INDEX ix_lab_runs_created ON fault_lab_runs (created_at);

ALTER TABLE execution_events ADD COLUMN lab_run_id VARCHAR(36) NULL;
ALTER TABLE execution_events ADD CONSTRAINT fk_events_lab_run FOREIGN KEY (lab_run_id)
    REFERENCES fault_lab_runs (id) ON DELETE CASCADE;
ALTER TABLE execution_events ADD CONSTRAINT ck_events_lab_simulated CHECK (lab_run_id IS NULL OR simulated);
CREATE INDEX ix_events_lab_run ON execution_events (lab_run_id, id);
