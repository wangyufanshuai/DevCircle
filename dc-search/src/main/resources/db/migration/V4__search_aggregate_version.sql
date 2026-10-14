-- Timestamp watermarks are not business versions. Preserve them for audit only.
-- Existing rows remain NULL until a versioned event is applied; never backfill from clocks.
ALTER TABLE search_consumer_dedup ADD COLUMN aggregate_version BIGINT NULL;
ALTER TABLE search_event_watermark ADD COLUMN aggregate_version BIGINT NULL;
