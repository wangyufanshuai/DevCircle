ALTER TABLE search_consumer_dedup
    ADD COLUMN lease_token CHAR(36) NULL;
