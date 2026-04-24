-- Per-user instrument selection (default: Bank Nifty)
ALTER TABLE users
    ADD COLUMN preferred_instrument_id BIGINT REFERENCES instruments (id);

UPDATE users u
SET preferred_instrument_id = i.id
FROM instruments i
WHERE u.preferred_instrument_id IS NULL
  AND i.name = 'BANKNIFTY';

ALTER TABLE users
    ALTER COLUMN preferred_instrument_id SET NOT NULL;
