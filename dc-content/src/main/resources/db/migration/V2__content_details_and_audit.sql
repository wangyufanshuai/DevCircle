ALTER TABLE post_content
    ADD COLUMN suspects JSON NULL,
    ADD COLUMN timeline JSON NULL,
    ADD COLUMN question VARCHAR(512) NULL,
    ADD COLUMN answer_keywords JSON NULL,
    ADD COLUMN explanation TEXT NULL,
    ADD COLUMN worldbuilding JSON NULL,
    ADD COLUMN cosmic_facts JSON NULL,
    ADD COLUMN related_post_ids JSON NULL;

CREATE TABLE IF NOT EXISTS audit_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NULL,
    action VARCHAR(96) NOT NULL,
    target_type VARCHAR(64) NOT NULL,
    target_id VARCHAR(96) NULL,
    ip VARCHAR(64) NULL,
    trace_id VARCHAR(128) NULL,
    metadata JSON NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_audit_action_created (action, created_at),
    INDEX idx_audit_user_created (user_id, created_at)
);

CREATE TABLE IF NOT EXISTS recommendation_impression (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    post_id BIGINT NOT NULL,
    scene VARCHAR(64) NOT NULL,
    algorithm VARCHAR(64) NOT NULL,
    score DOUBLE NOT NULL,
    features JSON NOT NULL,
    clicked BOOLEAN NOT NULL DEFAULT FALSE,
    liked BOOLEAN NOT NULL DEFAULT FALSE,
    favorited BOOLEAN NOT NULL DEFAULT FALSE,
    shown_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_recommend_user_scene_time (user_id, scene, shown_at),
    INDEX idx_recommend_post_time (post_id, shown_at)
);
