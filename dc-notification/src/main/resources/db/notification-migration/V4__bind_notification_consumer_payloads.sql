ALTER TABLE consumer_dedup
    ADD COLUMN event_fingerprint CHAR(64) NULL AFTER topic;

UPDATE consumer_dedup
SET event_fingerprint = REPEAT('0', 64)
WHERE event_fingerprint IS NULL;

ALTER TABLE consumer_dedup
    MODIFY COLUMN event_fingerprint CHAR(64) NOT NULL;
