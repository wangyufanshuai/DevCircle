ALTER TABLE deduction_idempotency
    ADD COLUMN request_fingerprint CHAR(64) NULL AFTER operation;

UPDATE deduction_idempotency
SET request_fingerprint = REPEAT('0', 64)
WHERE request_fingerprint IS NULL;

ALTER TABLE deduction_idempotency
    MODIFY COLUMN request_fingerprint CHAR(64) NOT NULL;

ALTER TABLE reading_progress_idempotency
    ADD COLUMN request_fingerprint CHAR(64) NULL AFTER post_id;

UPDATE reading_progress_idempotency
SET request_fingerprint = REPEAT('0', 64)
WHERE request_fingerprint IS NULL;

ALTER TABLE reading_progress_idempotency
    MODIFY COLUMN request_fingerprint CHAR(64) NOT NULL;
