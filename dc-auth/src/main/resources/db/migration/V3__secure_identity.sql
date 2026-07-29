ALTER TABLE user_account
    ADD COLUMN display_name VARCHAR(64) NULL AFTER username;

UPDATE user_account SET display_name = username WHERE display_name IS NULL;

ALTER TABLE user_account
    MODIFY display_name VARCHAR(64) NOT NULL;

ALTER TABLE refresh_token
    ADD COLUMN csrf_hash CHAR(64) NULL AFTER token_hash,
    ADD COLUMN family_id CHAR(36) NULL AFTER csrf_hash,
    ADD COLUMN used_at TIMESTAMP NULL AFTER revoked,
    ADD COLUMN replaced_by_hash CHAR(64) NULL AFTER used_at,
    ADD INDEX idx_refresh_family (family_id);

UPDATE refresh_token
SET csrf_hash = SHA2(CONCAT(token_hash, '-legacy-csrf'), 256),
    family_id = UUID()
WHERE csrf_hash IS NULL OR family_id IS NULL;

ALTER TABLE refresh_token
    MODIFY csrf_hash CHAR(64) NOT NULL,
    MODIFY family_id CHAR(36) NOT NULL;

INSERT INTO role(code, name) VALUES
    ('USER', '普通用户'),
    ('MODERATOR', '内容审核员'),
    ('ADMIN', '系统管理员')
ON DUPLICATE KEY UPDATE name = VALUES(name);

CREATE TABLE IF NOT EXISTS oauth_login_state (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    state_hash CHAR(64) NOT NULL UNIQUE,
    verifier VARCHAR(128) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    used_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_oauth_state_expiry (used, expires_at)
);
