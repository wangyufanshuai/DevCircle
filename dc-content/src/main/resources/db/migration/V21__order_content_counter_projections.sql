CREATE TABLE IF NOT EXISTS content_counter_projection_watermark (
    counter_type VARCHAR(16) NOT NULL,
    post_id BIGINT NOT NULL,
    source_version BIGINT NULL,
    occurred_at DATETIME(6) NULL,
    event_id CHAR(36) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (counter_type, post_id),
    INDEX idx_content_counter_projection_time (occurred_at)
);
