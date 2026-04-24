-- Product focus: AI (Gemini) predictions only for now
UPDATE ml_service_config
SET config_value = 'AI',
    updated_at   = now()
WHERE config_key = 'prediction_engine';
