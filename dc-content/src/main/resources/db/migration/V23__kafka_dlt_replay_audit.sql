CREATE TABLE IF NOT EXISTS kafka_dlt_replay (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    source_topic VARCHAR(128) NOT NULL,
    dlt_topic VARCHAR(132) NOT NULL,
    dlt_partition INT NOT NULL,
    dlt_offset BIGINT NOT NULL,
    event_id CHAR(36) NOT NULL,
    actor_id BIGINT NOT NULL,
    reason VARCHAR(512) NOT NULL,
    published_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_dlt_replay_idempotency (idempotency_key),
    UNIQUE KEY uk_dlt_replay_record (dlt_topic, dlt_partition, dlt_offset),
    INDEX idx_dlt_replay_event (event_id),
    INDEX idx_dlt_replay_actor_created (actor_id, created_at)
);
