-- Batch order (docs/batches-and-templates.md), H2 version of ../migrations/V3__batch_order.sql.
ALTER TABLE experiments ADD COLUMN batch_position INT NULL;
ALTER TABLE experiments ADD CONSTRAINT ck_experiments_batch_position
    CHECK (batch_position IS NULL OR batch_position >= 1);
CREATE INDEX ix_experiments_batch_order ON experiments (batch_id, batch_position);
