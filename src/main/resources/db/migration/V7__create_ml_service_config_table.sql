CREATE TABLE IF NOT EXISTS ml_service_config (
    id          BIGSERIAL PRIMARY KEY,
    config_key  VARCHAR(100) NOT NULL UNIQUE,
    config_value VARCHAR(500) NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO ml_service_config (config_key, config_value)
VALUES ('prediction_engine', 'ML')
ON CONFLICT (config_key) DO NOTHING;
