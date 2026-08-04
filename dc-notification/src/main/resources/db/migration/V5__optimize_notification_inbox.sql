-- Notification pagination uses an id cursor. Keep the historical created_at
-- indexes for one-version compatibility while adding indexes that match the
-- current unfiltered and type-filtered inbox queries.
ALTER TABLE notification
    ADD INDEX idx_notification_receiver_id (receiver_id, id),
    ADD INDEX idx_notification_receiver_type_id (receiver_id, type, id);
