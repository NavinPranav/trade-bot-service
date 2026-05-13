-- Enable OpenAI / ChatGPT provider
UPDATE ai_tools SET enabled = TRUE WHERE name = 'OPENAI';

-- GPT-4o series (multimodal, recommended for analysis)
INSERT INTO ai_models (tool_id, model_id, display_name, enabled, is_active)
SELECT id, 'gpt-4o',        'GPT-4o',         TRUE, FALSE FROM ai_tools WHERE name = 'OPENAI';

INSERT INTO ai_models (tool_id, model_id, display_name, enabled, is_active)
SELECT id, 'gpt-4o-mini',   'GPT-4o Mini',    TRUE, FALSE FROM ai_tools WHERE name = 'OPENAI';

-- GPT-4.1 series (released April 2025)
INSERT INTO ai_models (tool_id, model_id, display_name, enabled, is_active)
SELECT id, 'gpt-4.1',       'GPT-4.1',        TRUE, FALSE FROM ai_tools WHERE name = 'OPENAI';

INSERT INTO ai_models (tool_id, model_id, display_name, enabled, is_active)
SELECT id, 'gpt-4.1-mini',  'GPT-4.1 Mini',   TRUE, FALSE FROM ai_tools WHERE name = 'OPENAI';

INSERT INTO ai_models (tool_id, model_id, display_name, enabled, is_active)
SELECT id, 'gpt-4.1-nano',  'GPT-4.1 Nano',   TRUE, FALSE FROM ai_tools WHERE name = 'OPENAI';

-- GPT-4 Turbo (legacy, still available)
INSERT INTO ai_models (tool_id, model_id, display_name, enabled, is_active)
SELECT id, 'gpt-4-turbo',   'GPT-4 Turbo',    TRUE, FALSE FROM ai_tools WHERE name = 'OPENAI';

-- o-series reasoning models (suited for multi-step financial reasoning)
INSERT INTO ai_models (tool_id, model_id, display_name, enabled, is_active)
SELECT id, 'o1',            'o1',             TRUE, FALSE FROM ai_tools WHERE name = 'OPENAI';

INSERT INTO ai_models (tool_id, model_id, display_name, enabled, is_active)
SELECT id, 'o1-mini',       'o1 Mini',        TRUE, FALSE FROM ai_tools WHERE name = 'OPENAI';

INSERT INTO ai_models (tool_id, model_id, display_name, enabled, is_active)
SELECT id, 'o3-mini',       'o3 Mini',        TRUE, FALSE FROM ai_tools WHERE name = 'OPENAI';

INSERT INTO ai_models (tool_id, model_id, display_name, enabled, is_active)
SELECT id, 'o4-mini',       'o4 Mini',        TRUE, FALSE FROM ai_tools WHERE name = 'OPENAI';
