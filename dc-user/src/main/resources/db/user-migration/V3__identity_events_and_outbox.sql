ALTER TABLE user_profile DROP INDEX username;

CREATE TABLE IF NOT EXISTS consumer_dedup (
    event_id CHAR(36) PRIMARY KEY,
    topic VARCHAR(128) NOT NULL,
    consumed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS outbox_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id CHAR(36) NOT NULL UNIQUE,
    topic VARCHAR(128) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'NEW',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NULL,
    locked_at TIMESTAMP NULL,
    locked_by VARCHAR(64) NULL,
    last_error VARCHAR(1000) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP NULL,
    INDEX idx_outbox_ready (status, next_attempt_at, created_at)
);
