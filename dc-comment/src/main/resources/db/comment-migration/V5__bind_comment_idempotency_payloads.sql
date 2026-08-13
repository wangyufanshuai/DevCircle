ALTER TABLE idempotency_record
    ADD COLUMN request_fingerprint CHAR(64) NULL AFTER operation;

UPDATE idempotency_record
SET request_fingerprint = REPEAT('0', 64)
WHERE request_fingerprint IS NULL;

ALTER TABLE idempotency_record
    MODIFY COLUMN request_fingerprint CHAR(64) NOT NULL;
