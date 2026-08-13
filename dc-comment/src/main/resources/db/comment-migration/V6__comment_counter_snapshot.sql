CREATE TABLE IF NOT EXISTS post_comment_counter (
    post_id BIGINT PRIMARY KEY,
    visible_count BIGINT NOT NULL DEFAULT 0,
    projection_version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
);

UPDATE post_comment_counter SET visible_count = 0, projection_version = 0;

INSERT INTO post_comment_counter(post_id, visible_count)
SELECT post_id, COUNT(*)
FROM comment
WHERE status = 'VISIBLE'
GROUP BY post_id
ON DUPLICATE KEY UPDATE visible_count = VALUES(visible_count);
