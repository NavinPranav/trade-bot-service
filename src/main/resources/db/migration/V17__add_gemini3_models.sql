-- Add Gemini 3 model variants
INSERT INTO ai_models (tool_id, model_id, display_name, enabled, is_active)
SELECT id, 'gemini-3-flash-preview',       'Gemini 3 Flash Preview',       TRUE, FALSE FROM ai_tools WHERE name = 'GEMINI'
ON CONFLICT (tool_id, model_id) DO NOTHING;

INSERT INTO ai_models (tool_id, model_id, display_name, enabled, is_active)
SELECT id, 'gemini-3.1-flash-lite-preview', 'Gemini 3.1 Flash Lite Preview', TRUE, FALSE FROM ai_tools WHERE name = 'GEMINI'
ON CONFLICT (tool_id, model_id) DO NOTHING;

INSERT INTO ai_models (tool_id, model_id, display_name, enabled, is_active)
SELECT id, 'gemini-3.1-pro-preview',        'Gemini 3.1 Pro Preview',        TRUE, FALSE FROM ai_tools WHERE name = 'GEMINI'
ON CONFLICT (tool_id, model_id) DO NOTHING;
