-- Remove deprecated and non-existent AI models
--
-- Gemini 3.x "preview" models (gemini-3-flash-preview, gemini-3.1-*) were added
-- speculatively in V17 but do not exist in the Gemini API — they cause HTTP 404.
--
-- OpenAI deprecated models:
--   gpt-4-turbo  — retired April 2025, superseded by gpt-4o
--   o1-mini      — retired by OpenAI; use o4-mini or o3-mini instead
--
-- Safety: if any deprecated model happens to be active, promote gemini-2.5-flash
-- (Gemini) or gpt-4o (OpenAI) as the replacement before deleting.

-- Gemini: reassign active flag away from any deprecated model before deletion
UPDATE ai_models
SET is_active = FALSE
WHERE model_id IN (
    'gemini-3-flash-preview',
    'gemini-3.1-flash-lite-preview',
    'gemini-3.1-pro-preview'
) AND is_active = TRUE;

-- If no Gemini model is active after the above, promote gemini-2.5-flash
UPDATE ai_models
SET is_active = TRUE
WHERE model_id = 'gemini-2.5-flash'
  AND NOT EXISTS (SELECT 1 FROM ai_models WHERE is_active = TRUE);

-- OpenAI: reassign active flag away from deprecated OpenAI models before deletion
UPDATE ai_models
SET is_active = FALSE
WHERE model_id IN ('gpt-4-turbo', 'o1-mini') AND is_active = TRUE;

-- If no model is active after the above, promote gpt-4o
UPDATE ai_models
SET is_active = TRUE
WHERE model_id = 'gpt-4o'
  AND NOT EXISTS (SELECT 1 FROM ai_models WHERE is_active = TRUE);

-- Delete deprecated Gemini 3.x preview models (never existed in the API)
DELETE FROM ai_models
WHERE model_id IN (
    'gemini-3-flash-preview',
    'gemini-3.1-flash-lite-preview',
    'gemini-3.1-pro-preview'
);

-- Delete deprecated OpenAI models
DELETE FROM ai_models
WHERE model_id IN ('gpt-4-turbo', 'o1-mini');
