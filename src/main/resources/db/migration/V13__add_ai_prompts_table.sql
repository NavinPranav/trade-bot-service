CREATE TABLE ai_prompts (
    id          BIGSERIAL    PRIMARY KEY,
    label       VARCHAR(200) NOT NULL,
    prompt_text TEXT         NOT NULL,
    is_active   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_by  BIGINT       REFERENCES users(id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Enforces at most one active prompt at a time
CREATE UNIQUE INDEX uidx_ai_prompts_active ON ai_prompts (is_active) WHERE is_active = TRUE;
