-- Add user scoping, trading levels, and outcome fields to predictions
ALTER TABLE predictions
    ADD COLUMN user_id              BIGINT REFERENCES users(id),
    ADD COLUMN prediction_timestamp TIMESTAMPTZ,
    ADD COLUMN instrument_token     VARCHAR(20),
    ADD COLUMN current_sensex       NUMERIC(10,2),
    ADD COLUMN entry_price          NUMERIC(10,2),
    ADD COLUMN stop_loss            NUMERIC(10,2),
    ADD COLUMN target_sensex        NUMERIC(10,2),
    ADD COLUMN risk_reward          NUMERIC(5,2),
    ADD COLUMN valid_minutes        INTEGER,
    ADD COLUMN no_trade_zone        BOOLEAN DEFAULT FALSE,
    ADD COLUMN outcome_status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN actual_close_price   NUMERIC(10,2),
    ADD COLUMN actual_high_price    NUMERIC(10,2),
    ADD COLUMN actual_low_price     NUMERIC(10,2),
    ADD COLUMN stop_loss_hit        BOOLEAN,
    ADD COLUMN target_hit           BOOLEAN,
    ADD COLUMN actual_pnl_pct       NUMERIC(6,4),
    ADD COLUMN outcome_evaluated_at TIMESTAMPTZ;

CREATE INDEX idx_pred_user_horizon   ON predictions(user_id, horizon);
CREATE INDEX idx_pred_timestamp      ON predictions(prediction_timestamp);
CREATE INDEX idx_pred_pending        ON predictions(outcome_status) WHERE outcome_status = 'PENDING';

-- Separate table for large text fields (Gemini rationale)
CREATE TABLE prediction_details (
    id                BIGINT PRIMARY KEY REFERENCES predictions(id) ON DELETE CASCADE,
    prediction_reason TEXT,
    ai_quota_notice   VARCHAR(500)
);
