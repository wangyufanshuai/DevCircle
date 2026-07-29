ALTER TABLE deduction_attempt
    ADD COLUMN revealed BOOLEAN NOT NULL DEFAULT FALSE AFTER correct,
    ADD COLUMN revealed_at TIMESTAMP NULL AFTER created_at;

CREATE TABLE IF NOT EXISTS deduction_idempotency (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    post_id BIGINT NOT NULL,
    operation VARCHAR(24) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS reading_progress (
    user_id BIGINT NOT NULL,
    post_id BIGINT NOT NULL,
    progress_percent INT NOT NULL DEFAULT 0,
    anchor VARCHAR(160) NULL,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    last_read_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, post_id),
    INDEX idx_reading_progress_user_time (user_id, last_read_at)
);

CREATE TABLE IF NOT EXISTS reading_progress_idempotency (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    post_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE post_media
    ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'INLINE' AFTER media_id,
    ADD COLUMN alt_text VARCHAR(240) NULL AFTER role;
