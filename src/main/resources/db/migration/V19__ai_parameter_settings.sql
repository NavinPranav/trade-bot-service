-- Phase 4.4 — AI Parameter toggles
--
-- Captures which inputs the prediction service feeds to Gemini. Required
-- parameters (raw OHLCV, indicators, target horizon, multi-timeframe trend
-- context, current price) cannot be toggled because the model and the
-- post-AI guardrail both depend on them. Optional parameters (VIX,
-- checklist signal, news sentiment, prev-day levels, S/R levels) are
-- operator-controllable from the admin UI.
--
-- The Java backend syncs this table to the ML service on startup via
-- PUT /admin/parameters (mirrors the AiPromptService / AiModelService
-- pattern from Phase 4.0). The ML service then strips disabled sections
-- from the user payload before calling Gemini.

CREATE TABLE ai_parameter_settings (
    id              BIGSERIAL    PRIMARY KEY,
    parameter_key   VARCHAR(100) NOT NULL UNIQUE,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    is_required     BOOLEAN      NOT NULL DEFAULT FALSE,
    display_name    VARCHAR(150) NOT NULL,
    description     TEXT,
    sort_order      INTEGER      NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by      BIGINT       REFERENCES users(id) ON DELETE SET NULL
);

-- Required parameters (locked ON, sort first so the admin UI shows them
-- in a clear "always-on" group).
INSERT INTO ai_parameter_settings
    (parameter_key, enabled, is_required, display_name, description, sort_order) VALUES
    ('ohlcv_bars', TRUE, TRUE,
     'OHLCV bars (raw price/volume)',
     'The recent_ohlcv_bars window the AI walks for trend, breakouts, and reversal patterns. Required: without it the AI has no market data to read.',
     10),
    ('technical_indicators', TRUE, TRUE,
     'Technical indicators',
     'Pre-computed RSI, EMAs, MACD, ATR, Bollinger %B/width, and volume_ratio. Required: the prompt rules reference these by name and the post-AI gates depend on volume_ratio.',
     20),
    ('current_price', TRUE, TRUE,
     'Live spot price',
     'The current price (and intraday change %) anchoring entry/SL/target. Required: without it the AI cannot produce trade levels.',
     30),
    ('trend_context', TRUE, TRUE,
     'Multi-timeframe trend context',
     'Deterministic 5m + 15m regime label powering the trend-guardrail veto. Required: turning it off would let counter-trend signals slip through unchallenged.',
     40),
    ('target_minutes', TRUE, TRUE,
     'Prediction horizon (target_minutes)',
     'How far ahead the AI is asked to predict. Required: the prompt is templated on this value.',
     50),
    -- Optional parameters (toggleable from the UI).
    ('india_vix', TRUE, FALSE,
     'India VIX snapshot',
     'Recent India VIX values and % change since the previous bar. Use to widen stops in high-vol regimes.',
     110),
    ('checklist_signal', TRUE, FALSE,
     '8-step rule-based checklist',
     'Deterministic Bank-Nifty checklist (EMA trend, VWAP, RSI zone, pivots, candle, strike, risk, no-trade). Weighted into the AI conviction.',
     120),
    ('news_sentiment', TRUE, FALSE,
     'News sentiment feed',
     'NewsAPI headlines scored by VADER. Acts as a macro backdrop — bearish news raises the bar for a BUY signal and vice versa.',
     130),
    ('previous_day_levels', TRUE, FALSE,
     'Previous-day levels (PDH/PDL/CPR)',
     'Yesterday''s high, low, range, position-in-range, and the central pivot range. Used by the prev-day-structure factor and gap-analysis logic.',
     140),
    ('support_resistance_levels', TRUE, FALSE,
     'Support / Resistance / Pivot',
     'Today''s pivot, S1/S2, R1/R2 and the nearest support/resistance distance. Used by the S/R confluence factor.',
     150);

CREATE INDEX idx_ai_parameter_settings_sort_order ON ai_parameter_settings (sort_order);
