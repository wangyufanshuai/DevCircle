ALTER TABLE notification
    ADD COLUMN actor_id BIGINT NULL AFTER receiver_id,
    ADD COLUMN target_type VARCHAR(32) NULL AFTER content,
    ADD COLUMN target_id BIGINT NULL AFTER target_type,
    ADD COLUMN target_route VARCHAR(255) NULL AFTER target_id,
    ADD INDEX idx_notification_receiver_type_time (receiver_id, type, created_at);
