ALTER TABLE recommend_write_idempotency
    ADD COLUMN request_fingerprint CHAR(64) NULL AFTER operation;

UPDATE recommend_write_idempotency
SET request_fingerprint = REPEAT('0', 64)
WHERE request_fingerprint IS NULL;

ALTER TABLE recommend_write_idempotency
    MODIFY COLUMN request_fingerprint CHAR(64) NOT NULL;
