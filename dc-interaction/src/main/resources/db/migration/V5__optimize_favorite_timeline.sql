-- The uniqueness index protects writes but cannot satisfy the dashboard's
-- newest-first favorite timeline.
ALTER TABLE favorite_record
    ADD INDEX idx_favorite_user_created_post (user_id, created_at, post_id);
