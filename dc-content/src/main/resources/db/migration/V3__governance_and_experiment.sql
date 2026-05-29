CREATE TABLE IF NOT EXISTS moderation_rule (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    risk_level VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    expression VARCHAR(512) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS moderation_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    post_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    queue_name VARCHAR(64) NOT NULL,
    machine_result VARCHAR(64) NOT NULL,
    risk_level VARCHAR(32) NOT NULL,
    risk_score DOUBLE NOT NULL,
    reason VARCHAR(512) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    review_level VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
    sla_deadline TIMESTAMP NOT NULL,
    reviewer_id BIGINT NULL,
    reviewed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_moderation_queue_status (queue_name, status, risk_level, created_at),
    INDEX idx_moderation_post (post_id)
);

CREATE TABLE IF NOT EXISTS moderation_decision (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    post_id BIGINT NOT NULL,
    reviewer_id BIGINT NOT NULL,
    action VARCHAR(48) NOT NULL,
    reason VARCHAR(512) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_decision_task (task_id),
    INDEX idx_decision_post (post_id)
);

CREATE TABLE IF NOT EXISTS content_risk_signal (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    post_id BIGINT NULL,
    signal_type VARCHAR(64) NOT NULL,
    risk_score DOUBLE NOT NULL,
    description VARCHAR(512) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_risk_signal_user_time (user_id, created_at),
    INDEX idx_risk_signal_type_time (signal_type, created_at)
);

CREATE TABLE IF NOT EXISTS appeal_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    post_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    reason VARCHAR(512) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    handler_id BIGINT NULL,
    handled_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_appeal_status_created (status, created_at),
    INDEX idx_appeal_post (post_id)
);

CREATE TABLE IF NOT EXISTS user_risk_profile (
    user_id BIGINT PRIMARY KEY,
    status VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
    risk_score DOUBLE NOT NULL DEFAULT 0,
    reason VARCHAR(512) NOT NULL DEFAULT '',
    restricted_until TIMESTAMP NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_risk_status (status, updated_at)
);

CREATE TABLE IF NOT EXISTS recommend_experiment (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    experiment_id VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    scene VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    traffic_percent INT NOT NULL DEFAULT 0,
    weights JSON NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_recommend_exp_scene_status (scene, status)
);

CREATE TABLE IF NOT EXISTS recommend_experiment_bucket (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    experiment_id VARCHAR(64) NOT NULL,
    bucket VARCHAR(64) NOT NULL,
    traffic_percent INT NOT NULL,
    algorithm VARCHAR(64) NOT NULL,
    weights JSON NOT NULL,
    UNIQUE KEY uk_experiment_bucket (experiment_id, bucket)
);

CREATE TABLE IF NOT EXISTS recommend_event_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    post_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    scene VARCHAR(64) NOT NULL,
    experiment_id VARCHAR(64) NULL,
    bucket VARCHAR(64) NULL,
    request_id VARCHAR(96) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_recommend_event_user_time (user_id, created_at),
    INDEX idx_recommend_event_post_time (post_id, created_at),
    INDEX idx_recommend_event_exp_type (experiment_id, event_type)
);

INSERT INTO moderation_rule(code, name, risk_level, expression)
VALUES
    ('HIGH_FREQ_POST', '短时间高频发帖', 'MEDIUM', 'posts_10m >= 5'),
    ('SENSITIVE_TAG', '敏感标签或高风险主题', 'HIGH', 'tag in risk_tags'),
    ('DUPLICATE_CONTENT', '重复内容', 'MEDIUM', 'same_title_recently'),
    ('UPLOAD_ANOMALY', '上传类型异常', 'HIGH', 'mime_magic_mismatch')
ON DUPLICATE KEY UPDATE name = VALUES(name), risk_level = VALUES(risk_level), expression = VALUES(expression);

INSERT INTO recommend_experiment(experiment_id, name, scene, status, traffic_percent, weights)
VALUES
    ('exp-theme-feed-v1', '主题内容多路召回实验', 'home', 'ACTIVE', 100, JSON_OBJECT('theme', 1.2, 'hot', 0.8, 'freshness', 0.6, 'follow', 0.7)),
    ('exp-deduction-solve-v1', '推理馆解谜完成率实验', 'deduction', 'ACTIVE', 100, JSON_OBJECT('solvable', 1.4, 'difficulty', 0.5, 'hot', 0.6))
ON DUPLICATE KEY UPDATE status = VALUES(status), traffic_percent = VALUES(traffic_percent), weights = VALUES(weights);

INSERT INTO recommend_experiment_bucket(experiment_id, bucket, traffic_percent, algorithm, weights)
VALUES
    ('exp-theme-feed-v1', 'control', 50, 'THEME_LINEAR_MODEL', JSON_OBJECT('theme', 1.0, 'hot', 1.0)),
    ('exp-theme-feed-v1', 'treatment', 50, 'THEME_LINEAR_MODEL', JSON_OBJECT('theme', 1.3, 'follow', 0.9)),
    ('exp-deduction-solve-v1', 'control', 50, 'THEME_LINEAR_MODEL', JSON_OBJECT('solvable', 1.0)),
    ('exp-deduction-solve-v1', 'treatment', 50, 'THEME_LINEAR_MODEL', JSON_OBJECT('solvable', 1.5))
ON DUPLICATE KEY UPDATE traffic_percent = VALUES(traffic_percent), algorithm = VALUES(algorithm), weights = VALUES(weights);
