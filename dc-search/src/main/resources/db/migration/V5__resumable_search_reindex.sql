CREATE TABLE search_reindex_job (
    job_id VARCHAR(36) PRIMARY KEY,
    phase VARCHAR(16) NOT NULL,
    cursor_value VARCHAR(256) NOT NULL DEFAULT '',
    copied_count BIGINT NOT NULL DEFAULT 0,
    verified_count BIGINT NOT NULL DEFAULT 0,
    copy_hash VARCHAR(64) NOT NULL,
    verify_hash VARCHAR(64) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    last_error VARCHAR(1024) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- A short transaction owns this row for one bounded batch. Connection loss releases
-- the lock and rolls back the checkpoint; any already-written ES documents replay safely.
CREATE TABLE search_reindex_guard (
    guard_id INT PRIMARY KEY,
    active_job_id VARCHAR(36) NULL
);
INSERT INTO search_reindex_guard(guard_id, active_job_id) VALUES (1, NULL);
