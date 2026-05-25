CREATE TABLE IF NOT EXISTS category (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(64) NOT NULL UNIQUE,
    sort_order INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS tag (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(64) NOT NULL UNIQUE,
    use_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS post (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    author_id BIGINT NOT NULL,
    category_id BIGINT NULL,
    type VARCHAR(32) NOT NULL DEFAULT 'TEXT',
    title VARCHAR(160) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    visibility VARCHAR(32) NOT NULL DEFAULT 'PUBLIC',
    like_count BIGINT NOT NULL DEFAULT 0,
    comment_count BIGINT NOT NULL DEFAULT 0,
    view_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_post_status_created (status, created_at),
    INDEX idx_post_type_created (type, created_at),
    INDEX idx_post_author_created (author_id, created_at)
);

-- idx_post_status_created supports moderation queues and latest feeds.
-- idx_post_author_created supports profile timelines.

CREATE TABLE IF NOT EXISTS post_content (
    post_id BIGINT PRIMARY KEY,
    markdown MEDIUMTEXT NOT NULL,
    html MEDIUMTEXT NULL
);

CREATE TABLE IF NOT EXISTS post_tag (
    post_id BIGINT NOT NULL,
    tag_id BIGINT NOT NULL,
    PRIMARY KEY (post_id, tag_id),
    INDEX idx_post_tag_tag (tag_id)
);

CREATE TABLE IF NOT EXISTS media_asset (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    object_key VARCHAR(512) NOT NULL UNIQUE,
    url VARCHAR(1024) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT NOT NULL,
    width INT NULL,
    height INT NULL,
    duration_seconds INT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING_REVIEW',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_media_status_created (status, created_at)
);

CREATE TABLE IF NOT EXISTS post_media (
    post_id BIGINT NOT NULL,
    media_id BIGINT NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    PRIMARY KEY (post_id, media_id),
    INDEX idx_post_media_media (media_id)
);

CREATE TABLE IF NOT EXISTS post_audit_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    post_id BIGINT NOT NULL,
    auditor_id BIGINT NULL,
    action VARCHAR(32) NOT NULL,
    reason VARCHAR(255) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_audit_post (post_id)
);

CREATE TABLE IF NOT EXISTS outbox_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id CHAR(36) NOT NULL UNIQUE,
    topic VARCHAR(128) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'NEW',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP NULL,
    INDEX idx_outbox_status_created (status, created_at)
);

-- idx_outbox_status_created supports reliable event publisher scans.
