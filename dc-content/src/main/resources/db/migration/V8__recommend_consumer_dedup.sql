CREATE TABLE IF NOT EXISTS recommend_consumer_dedup (
    event_id CHAR(36) PRIMARY KEY,
    topic VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PROCESSING',
    attempts INT NOT NULL DEFAULT 1,
    last_error VARCHAR(1000) NULL,
    consumed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_recommend_consumer_status (status, updated_at)
);
