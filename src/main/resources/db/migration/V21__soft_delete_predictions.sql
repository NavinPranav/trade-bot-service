-- Soft-delete support for predictions.
-- Records with deleted = TRUE are excluded from all history queries and metrics
-- but remain in the DB for audit / outcome-resolution purposes.
ALTER TABLE predictions ADD COLUMN IF NOT EXISTS deleted BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_pred_not_deleted ON predictions (deleted) WHERE deleted = FALSE;
