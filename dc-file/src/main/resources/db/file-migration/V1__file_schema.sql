CREATE TABLE IF NOT EXISTS media_asset (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    object_key VARCHAR(512) NOT NULL UNIQUE,
    url VARCHAR(1024) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT NOT NULL,
    width INT NULL,
    height INT NULL,
    duration_seconds INT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING_REVIEW',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_media_status_created (status, created_at)
);
