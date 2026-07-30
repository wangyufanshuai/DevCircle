CREATE TABLE IF NOT EXISTS search_consumer_dedup (
    event_id CHAR(36) PRIMARY KEY,
    topic VARCHAR(128) NOT NULL,
    post_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PROCESSING',
    attempts INT NOT NULL DEFAULT 1,
    last_error VARCHAR(1000) NULL,
    processed_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_search_consumer_status (status, updated_at)
);

CREATE TABLE IF NOT EXISTS search_event_watermark (
    post_id BIGINT PRIMARY KEY,
    occurred_at DATETIME(6) NULL,
    last_event_id CHAR(36) NULL,
    last_action VARCHAR(16) NULL,
    lease_owner CHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_search_event_lease (lease_until)
);
