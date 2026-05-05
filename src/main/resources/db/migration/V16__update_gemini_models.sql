-- Remove outdated Gemini models (1.5-generation and preview builds)
DELETE FROM ai_models
WHERE model_id IN ('gemini-1.5-flash', 'gemini-1.5-pro', 'gemini-2.5-flash-preview');

-- Safety reset: ensure no stale active flag remains before promoting new default
UPDATE ai_models SET is_active = FALSE WHERE is_active = TRUE;

-- Add gemini-2.0-flash-lite if not already present
INSERT INTO ai_models (tool_id, model_id, display_name, enabled, is_active)
SELECT id, 'gemini-2.0-flash-lite', 'Gemini 2.0 Flash Lite', TRUE, FALSE
FROM ai_tools WHERE name = 'GEMINI'
ON CONFLICT (tool_id, model_id) DO NOTHING;

-- Add gemini-2.5-flash (stable) — set as the active model
INSERT INTO ai_models (tool_id, model_id, display_name, enabled, is_active)
SELECT id, 'gemini-2.5-flash', 'Gemini 2.5 Flash', TRUE, FALSE
FROM ai_tools WHERE name = 'GEMINI'
ON CONFLICT (tool_id, model_id) DO NOTHING;

-- Add gemini-2.5-pro (stable, most capable)
INSERT INTO ai_models (tool_id, model_id, display_name, enabled, is_active)
SELECT id, 'gemini-2.5-pro', 'Gemini 2.5 Pro', TRUE, FALSE
FROM ai_tools WHERE name = 'GEMINI'
ON CONFLICT (tool_id, model_id) DO NOTHING;

-- Promote gemini-2.5-flash as the active model
UPDATE ai_models
SET is_active = TRUE
WHERE model_id = 'gemini-2.5-flash';
