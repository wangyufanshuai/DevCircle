UPDATE tag t
SET use_count = (
    SELECT COUNT(*)
    FROM post_tag pt
    WHERE pt.tag_id = t.id
);
