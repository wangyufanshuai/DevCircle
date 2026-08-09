ALTER TABLE file_idempotency_record
    ADD COLUMN request_fingerprint CHAR(64) NULL AFTER operation;

UPDATE file_idempotency_record
SET request_fingerprint = REPEAT('0', 64)
WHERE request_fingerprint IS NULL;

ALTER TABLE file_idempotency_record
    MODIFY COLUMN request_fingerprint CHAR(64) NOT NULL;
