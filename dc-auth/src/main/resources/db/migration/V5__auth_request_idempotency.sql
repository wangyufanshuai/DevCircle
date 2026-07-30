CREATE TABLE IF NOT EXISTS auth_idempotency_record (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    operation VARCHAR(32) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PROCESSING',
    response_payload TEXT NULL,
    user_id BIGINT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_auth_idempotency_expiry (expires_at),
    INDEX idx_auth_idempotency_user (user_id, created_at)
);
