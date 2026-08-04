-- Root comments and replies are paged by their monotonic id. These indexes
-- avoid scanning every comment for a post and preserve the legacy visible
-- comment endpoint during the compatibility window.
ALTER TABLE comment
    ADD INDEX idx_comment_post_status_id (post_id, status, id),
    ADD INDEX idx_comment_thread_root_id (post_id, parent_id, id),
    ADD INDEX idx_comment_thread_reply_id (post_id, root_id, id);
