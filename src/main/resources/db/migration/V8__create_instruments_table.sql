CREATE TABLE instruments (
    id          BIGSERIAL       PRIMARY KEY,
    name        VARCHAR(50)     NOT NULL,
    display_name VARCHAR(100)   NOT NULL,
    exchange    VARCHAR(20)     NOT NULL,
    token       VARCHAR(30)     NOT NULL,
    exchange_type INTEGER       NOT NULL DEFAULT 1,
    market_type VARCHAR(20)     NOT NULL DEFAULT 'INDEX',
    active      BOOLEAN         NOT NULL DEFAULT FALSE,
    display_order INTEGER       NOT NULL DEFAULT 0,
    created_at  TIMESTAMP       NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_instruments_token_exchange UNIQUE (token, exchange)
);

CREATE INDEX idx_instruments_active ON instruments (active);
CREATE INDEX idx_instruments_market_type ON instruments (market_type);

-- Seed popular Angel One SmartAPI instruments
INSERT INTO instruments (name, display_name, exchange, token, exchange_type, market_type, active, display_order) VALUES
    ('BANKNIFTY',   'Bank Nifty',       'nse_cm', '26009',     1, 'INDEX',     TRUE,  1),
    ('NIFTY',       'Nifty 50',         'nse_cm', '26000',     1, 'INDEX',     FALSE, 2),
    ('SENSEX',      'BSE Sensex',       'bse_cm', '99919000',  3, 'INDEX',     FALSE, 3),
    ('FINNIFTY',    'Fin Nifty',        'nse_cm', '26037',     1, 'INDEX',     FALSE, 4),
    ('MIDCPNIFTY',  'Midcap Nifty',     'nse_cm', '26074',     1, 'INDEX',     FALSE, 5),
    ('INDIA VIX',   'India VIX',        'nse_cm', '26017',     1, 'INDEX',     FALSE, 6),
    ('RELIANCE',    'Reliance Industries','nse_cm','2885',      1, 'EQUITY',    FALSE, 10),
    ('TCS',         'Tata Consultancy', 'nse_cm', '11536',     1, 'EQUITY',    FALSE, 11),
    ('HDFCBANK',    'HDFC Bank',        'nse_cm', '1333',      1, 'EQUITY',    FALSE, 12),
    ('INFY',        'Infosys',          'nse_cm', '1594',      1, 'EQUITY',    FALSE, 13),
    ('ICICIBANK',   'ICICI Bank',       'nse_cm', '4963',      1, 'EQUITY',    FALSE, 14),
    ('SBIN',        'State Bank of India','nse_cm','3045',      1, 'EQUITY',    FALSE, 15),
    ('TATAMOTORS',  'Tata Motors',      'nse_cm', '3456',      1, 'EQUITY',    FALSE, 16),
    ('USDINR',      'USD/INR',          'cds',    '99926004',  13,'CURRENCY',  FALSE, 20),
    ('CRUDEOIL',    'Crude Oil Futures', 'mcx_fo','486502',    5, 'COMMODITY', FALSE, 30),
    ('GOLD',        'Gold Futures',     'mcx_fo', '447498',    5, 'COMMODITY', FALSE, 31),
    ('SILVER',      'Silver Futures',   'mcx_fo', '453884',    5, 'COMMODITY', FALSE, 32)
ON CONFLICT DO NOTHING;
