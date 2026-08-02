ALTER TABLE recommend_consumer_dedup
    ADD COLUMN lease_token CHAR(36) NULL AFTER attempts;
