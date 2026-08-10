ALTER TABLE post_counter
    ADD COLUMN projection_version BIGINT NOT NULL DEFAULT 0 AFTER favorite_count;
