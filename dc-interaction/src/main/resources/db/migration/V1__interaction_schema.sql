CREATE TABLE IF NOT EXISTS like_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_like_user_target (user_id, target_type, target_id)
);

-- uk_like_user_target is the database-level idempotency guard for likes.

CREATE TABLE IF NOT EXISTS favorite_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    post_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_favorite_user_post (user_id, post_id)
);

-- uk_favorite_user_post is the database-level idempotency guard for favorites.

CREATE TABLE IF NOT EXISTS view_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NULL,
    post_id BIGINT NOT NULL,
    ip_hash VARCHAR(128) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_view_post_created (post_id, created_at)
);

CREATE TABLE IF NOT EXISTS user_counter (
    user_id BIGINT PRIMARY KEY,
    follower_count BIGINT NOT NULL DEFAULT 0,
    following_count BIGINT NOT NULL DEFAULT 0,
    post_count BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS post_counter (
    post_id BIGINT PRIMARY KEY,
    like_count BIGINT NOT NULL DEFAULT 0,
    favorite_count BIGINT NOT NULL DEFAULT 0,
    comment_count BIGINT NOT NULL DEFAULT 0,
    view_count BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS consumer_dedup (
    event_id CHAR(36) PRIMARY KEY,
    topic VARCHAR(128) NOT NULL,
    consumed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- consumer_dedup prevents repeated Kafka deliveries from duplicating counters or notifications.
