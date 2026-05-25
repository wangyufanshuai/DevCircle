CREATE TABLE IF NOT EXISTS notification (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    receiver_id BIGINT NOT NULL,
    type VARCHAR(64) NOT NULL,
    title VARCHAR(128) NOT NULL,
    content VARCHAR(512) NOT NULL,
    read_flag BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_notification_receiver_read (receiver_id, read_flag, created_at)
);

CREATE TABLE IF NOT EXISTS notification_setting (
    user_id BIGINT PRIMARY KEY,
    comment_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    like_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    audit_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    system_enabled BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS consumer_dedup (
    event_id CHAR(36) PRIMARY KEY,
    topic VARCHAR(128) NOT NULL,
    consumed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

