ALTER TABLE outbox_event
    ADD COLUMN attempts INT NOT NULL DEFAULT 0 AFTER status,
    ADD COLUMN next_attempt_at TIMESTAMP NULL AFTER attempts,
    ADD COLUMN locked_at TIMESTAMP NULL AFTER next_attempt_at,
    ADD COLUMN locked_by VARCHAR(64) NULL AFTER locked_at,
    ADD COLUMN last_error VARCHAR(1000) NULL AFTER locked_by,
    ADD INDEX idx_outbox_ready (status, next_attempt_at, created_at);
