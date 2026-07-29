CREATE TABLE IF NOT EXISTS recommend_write_idempotency (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    operation VARCHAR(48) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS recommendation_exposure (
    user_id BIGINT NOT NULL,
    post_id BIGINT NOT NULL,
    scene VARCHAR(64) NOT NULL,
    exposed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, post_id, scene),
    INDEX idx_exposure_user_time (user_id, exposed_at)
);

ALTER TABLE recommend_event_log
    ADD UNIQUE KEY uk_recommend_event_request (user_id, request_id);
