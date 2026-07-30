CREATE TABLE IF NOT EXISTS interaction_projection_dedup (
    event_id CHAR(36) PRIMARY KEY,
    topic VARCHAR(128) NOT NULL,
    consumed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_interaction_projection_consumed (consumed_at)
);
