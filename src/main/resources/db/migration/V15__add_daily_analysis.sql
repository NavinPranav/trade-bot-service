CREATE TABLE daily_analysis (
    id            BIGSERIAL PRIMARY KEY,
    analysis_date DATE      NOT NULL UNIQUE,
    analysis_data TEXT      NOT NULL,
    prediction_count INT    NOT NULL DEFAULT 0,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE daily_analysis_reads (
    user_id           BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    daily_analysis_id BIGINT NOT NULL REFERENCES daily_analysis(id) ON DELETE CASCADE,
    read_at           TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, daily_analysis_id)
);

CREATE INDEX idx_daily_analysis_date ON daily_analysis(analysis_date DESC);
CREATE INDEX idx_daily_analysis_reads_user ON daily_analysis_reads(user_id);
