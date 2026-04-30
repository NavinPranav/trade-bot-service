-- Add AI tool/model tracking columns to predictions
ALTER TABLE predictions
    ADD COLUMN IF NOT EXISTS ai_tool  VARCHAR(50),
    ADD COLUMN IF NOT EXISTS ai_model VARCHAR(100);

-- Catalog of supported AI tools
CREATE TABLE ai_tools (
    id           BIGSERIAL    PRIMARY KEY,
    name         VARCHAR(50)  NOT NULL UNIQUE,
    display_name VARCHAR(100) NOT NULL,
    enabled      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Models belonging to a tool; at most one active across all tools
CREATE TABLE ai_models (
    id           BIGSERIAL    PRIMARY KEY,
    tool_id      BIGINT       NOT NULL REFERENCES ai_tools(id) ON DELETE CASCADE,
    model_id     VARCHAR(100) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    is_active    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (tool_id, model_id)
);

CREATE UNIQUE INDEX uidx_ai_models_active ON ai_models (is_active) WHERE is_active = TRUE;

-- Seed: tools (only GEMINI enabled by default)
INSERT INTO ai_tools (name, display_name, enabled) VALUES
    ('GEMINI',    'Google Gemini', TRUE),
    ('OPENAI',    'OpenAI',        FALSE),
    ('ANTHROPIC', 'Anthropic',     FALSE);

-- Seed: Gemini models
INSERT INTO ai_models (tool_id, model_id, display_name, enabled, is_active)
SELECT id, 'gemini-1.5-flash',         'Gemini 1.5 Flash',         TRUE,  TRUE
FROM ai_tools WHERE name = 'GEMINI';

INSERT INTO ai_models (tool_id, model_id, display_name, enabled, is_active)
SELECT id, 'gemini-2.0-flash',         'Gemini 2.0 Flash',         TRUE,  FALSE
FROM ai_tools WHERE name = 'GEMINI';

INSERT INTO ai_models (tool_id, model_id, display_name, enabled, is_active)
SELECT id, 'gemini-1.5-pro',           'Gemini 1.5 Pro',           TRUE,  FALSE
FROM ai_tools WHERE name = 'GEMINI';

INSERT INTO ai_models (tool_id, model_id, display_name, enabled, is_active)
SELECT id, 'gemini-2.5-flash-preview', 'Gemini 2.5 Flash Preview', TRUE,  FALSE
FROM ai_tools WHERE name = 'GEMINI';
