package io.github.devcircle.content.infrastructure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

import static io.github.devcircle.content.domain.PostMetadataPolicy.normalizeTags;

/** Owns tag identity, post-tag relations, and their denormalized usage counters. */
@Repository
public class PostTagStore {
    private final JdbcClient jdbcClient;

    public PostTagStore(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    public void replace(Long postId, List<String> tags) {
        List<Long> existingTagIds = jdbcClient.sql("SELECT tag_id FROM post_tag WHERE post_id = :postId")
                .param("postId", postId)
                .query(Long.class)
                .list();
        if (!existingTagIds.isEmpty()) {
            jdbcClient.sql("DELETE FROM post_tag WHERE post_id = :postId")
                    .param("postId", postId)
                    .update();
            jdbcClient.sql("UPDATE tag SET use_count = GREATEST(use_count - 1, 0) WHERE id IN (:tagIds)")
                    .param("tagIds", existingTagIds)
                    .update();
        }
        for (String tagName : normalizeTags(tags)) {
            attach(postId, tagName);
        }
    }

    private void attach(Long postId, String name) {
        Long tagId = ensureTag(name);
        int inserted = jdbcClient.sql("INSERT IGNORE INTO post_tag(post_id, tag_id) VALUES (:postId, :tagId)")
                .param("postId", postId)
                .param("tagId", tagId)
                .update();
        if (inserted == 1) {
            jdbcClient.sql("UPDATE tag SET use_count = use_count + 1 WHERE id = :tagId")
                    .param("tagId", tagId)
                    .update();
        }
    }

    private Long ensureTag(String name) {
        jdbcClient.sql("INSERT IGNORE INTO tag(name, use_count) VALUES (:name, 0)")
                .param("name", name)
                .update();
        return jdbcClient.sql("SELECT id FROM tag WHERE name = :name")
                .param("name", name)
                .query(Long.class)
                .single();
    }
}
