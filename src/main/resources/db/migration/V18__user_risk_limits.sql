-- Per-account trading guardrails (signal-level; not broker execution).
ALTER TABLE users ADD COLUMN risk_trading_halted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN risk_max_signals_per_day INTEGER NULL;
ALTER TABLE users ADD COLUMN risk_max_daily_loss_pct NUMERIC(10, 4) NULL;

COMMENT ON COLUMN users.risk_trading_halted IS 'Manual kill switch: directional signals are forced to HOLD.';
COMMENT ON COLUMN users.risk_max_signals_per_day IS 'Max BUY/SELL/BULLISH/BEARISH predictions persisted per IST calendar day; NULL = no limit.';
COMMENT ON COLUMN users.risk_max_daily_loss_pct IS 'If sum of resolved actual_pnl_pct for today <= -this value, block new directional signals; NULL = no limit.';
