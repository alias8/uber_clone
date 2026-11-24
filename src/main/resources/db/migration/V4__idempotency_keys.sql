-- One row per (rider, Idempotency-Key) on POST /rides. Written in the same transaction as the
-- ride it created, so a key exists exactly when its ride does. See service/IdempotencyKeys.kt.
CREATE TABLE idempotency_keys (
    user_id      VARCHAR(255) NOT NULL,
    idem_key     VARCHAR(255) NOT NULL,
    request_hash CHAR(64)     NOT NULL,  -- SHA-256 of the request body: same key + different body = 422
    ride_id      VARCHAR(255) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, idem_key)      -- scoped per user: one rider can't hit another's key
);

-- For the hourly cleanup of keys older than 24 h.
CREATE INDEX idx_idempotency_keys_created_at ON idempotency_keys (created_at);
