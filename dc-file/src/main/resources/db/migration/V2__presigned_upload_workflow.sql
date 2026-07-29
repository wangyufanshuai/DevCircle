ALTER TABLE media_asset
    ADD COLUMN uploader_id BIGINT NULL AFTER id,
    ADD COLUMN expected_size BIGINT NULL AFTER size_bytes,
    ADD COLUMN upload_status VARCHAR(32) NOT NULL DEFAULT 'COMPLETED' AFTER status,
    ADD COLUMN upload_expires_at TIMESTAMP NULL AFTER upload_status,
    ADD INDEX idx_media_uploader_status (uploader_id, upload_status, created_at);

CREATE TABLE IF NOT EXISTS file_idempotency_record (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    operation VARCHAR(48) NOT NULL,
    resource_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
