-- Fix deprecated index tokens (old 26xxx → new 999xxxxx format)
UPDATE instruments SET token = '99926009' WHERE name = 'BANKNIFTY'   AND token = '26009';
UPDATE instruments SET token = '99926000' WHERE name = 'NIFTY'       AND token = '26000';
UPDATE instruments SET token = '99926017' WHERE name = 'INDIA VIX'   AND token = '26017';
UPDATE instruments SET token = '99926074' WHERE name = 'MIDCPNIFTY'  AND token = '26074';

-- Remove discontinued FINNIFTY (merged by NSE)
DELETE FROM instruments WHERE name = 'FINNIFTY';

-- Remove commodity futures (tokens expire with each contract, not suitable for static seed)
DELETE FROM instruments WHERE market_type = 'COMMODITY';

-- Remove USDINR (token unverified)
DELETE FROM instruments WHERE name = 'USDINR';

-- Add verified equity instruments if missing
INSERT INTO instruments (name, display_name, exchange, token, exchange_type, market_type, active, display_order)
VALUES
    ('KOTAKBANK', 'Kotak Mahindra Bank', 'nse_cm', '1922',  1, 'EQUITY', FALSE, 17),
    ('LT',        'Larsen & Toubro',     'nse_cm', '11483', 1, 'EQUITY', FALSE, 18),
    ('AXISBANK',  'Axis Bank',           'nse_cm', '5900',  1, 'EQUITY', FALSE, 19)
ON CONFLICT DO NOTHING;

-- Fix Midcap Nifty display name
UPDATE instruments SET display_name = 'Nifty Midcap Select' WHERE name = 'MIDCPNIFTY';
