CREATE TABLE IF NOT EXISTS user_profile (
    user_id BIGINT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    avatar_url VARCHAR(512) NULL,
    bio VARCHAR(512) NULL,
    follower_count BIGINT NOT NULL DEFAULT 0,
    following_count BIGINT NOT NULL DEFAULT 0,
    post_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS user_follow (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    follower_id BIGINT NOT NULL,
    followee_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_follow_pair (follower_id, followee_id),
    INDEX idx_followee (followee_id)
);
