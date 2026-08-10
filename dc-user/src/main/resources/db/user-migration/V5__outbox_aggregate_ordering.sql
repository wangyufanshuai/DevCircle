ALTER TABLE outbox_event
    ADD INDEX idx_outbox_aggregate_order (topic, aggregate_id, id, status);
