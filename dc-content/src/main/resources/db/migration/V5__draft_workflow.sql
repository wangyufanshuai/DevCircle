ALTER TABLE post
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER status,
    ADD COLUMN last_saved_at TIMESTAMP NULL AFTER version,
    ADD COLUMN submitted_at TIMESTAMP NULL AFTER last_saved_at,
    ADD COLUMN review_reason VARCHAR(512) NULL AFTER submitted_at,
    ADD INDEX idx_post_author_status_saved (author_id, status, last_saved_at);

CREATE TABLE IF NOT EXISTS content_idempotency_record (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    operation VARCHAR(48) NOT NULL,
    resource_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_content_idem_user_created (user_id, created_at)
);
