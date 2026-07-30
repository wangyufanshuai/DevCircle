CREATE TABLE IF NOT EXISTS recommend_follow_projection (
    viewer_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    followed BOOLEAN NOT NULL,
    source_event_id CHAR(36) NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (viewer_id, author_id),
    UNIQUE KEY uk_recommend_follow_source_event (source_event_id),
    INDEX idx_recommend_follow_viewer_active (viewer_id, followed, author_id)
);
