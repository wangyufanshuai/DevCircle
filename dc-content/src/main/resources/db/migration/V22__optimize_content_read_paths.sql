-- The existing status-only and theme-only indexes remain useful for generic
-- feeds. This index targets the fully filtered channel cursor without removing
-- the one-version compatibility indexes.
ALTER TABLE post
    ADD INDEX idx_post_channel_cursor
        (status, theme, content_kind, difficulty, created_at, id),
    ADD INDEX idx_post_author_updated
        (author_id, updated_at, id);
