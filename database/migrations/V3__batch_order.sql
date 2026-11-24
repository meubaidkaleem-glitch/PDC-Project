-- Batches (docs/batches-and-templates.md) run their experiments one after another in the order they were given.
ALTER TABLE experiments
    ADD COLUMN batch_position INT NULL AFTER batch_id,
    ADD CONSTRAINT ck_experiments_batch_position CHECK (batch_position IS NULL OR batch_position >= 1),
    ADD INDEX ix_experiments_batch_order (batch_id, batch_position);
