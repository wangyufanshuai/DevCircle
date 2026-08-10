CREATE TABLE IF NOT EXISTS user_idempotency (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    target_user_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_idempotency (user_id, idempotency_key),
    INDEX idx_user_idempotency_created (created_at)
);
