package io.github.devcircle.content.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.common.event.PostCreatedPayload;
import io.github.devcircle.common.model.PostSummary;
import io.github.devcircle.content.api.CreatePostRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Repository
public class PostRepository {
    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public PostRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.objectMapper = objectMapper;
    }

    public List<PostSummary> list(int page, int size) {
        int offset = Math.max(page - 1, 0) * size;
        return jdbcClient.sql("""
                        SELECT p.id, p.title, LEFT(pc.markdown, 140) AS excerpt, p.author_id, p.status,
                               p.like_count, p.comment_count, p.view_count, p.created_at,
                               GROUP_CONCAT(t.name ORDER BY t.name SEPARATOR ',') AS tags
                        FROM post p
                        JOIN post_content pc ON pc.post_id = p.id
                        LEFT JOIN post_tag pt ON pt.post_id = p.id
                        LEFT JOIN tag t ON t.id = pt.tag_id
                        GROUP BY p.id, p.title, pc.markdown, p.author_id, p.status, p.like_count, p.comment_count, p.view_count, p.created_at
                        ORDER BY p.created_at DESC
                        LIMIT :size OFFSET :offset
                        """)
                .param("size", size)
                .param("offset", offset)
                .query((rs, rowNum) -> new PostSummary(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getString("excerpt"),
                        "user-" + rs.getLong("author_id"),
                        splitTags(rs.getString("tags")),
                        rs.getLong("like_count"),
                        rs.getLong("comment_count"),
                        rs.getLong("view_count"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant()
                ))
                .list();
    }

    public long count() {
        Long count = jdbcClient.sql("SELECT COUNT(*) FROM post").query(Long.class).single();
        return count == null ? 0 : count;
    }

    public PostSummary detail(Long id) {
        return jdbcClient.sql("""
                        SELECT p.id, p.title, LEFT(pc.markdown, 140) AS excerpt, p.author_id, p.status,
                               p.like_count, p.comment_count, p.view_count, p.created_at,
                               GROUP_CONCAT(t.name ORDER BY t.name SEPARATOR ',') AS tags
                        FROM post p
                        JOIN post_content pc ON pc.post_id = p.id
                        LEFT JOIN post_tag pt ON pt.post_id = p.id
                        LEFT JOIN tag t ON t.id = pt.tag_id
                        WHERE p.id = :id
                        GROUP BY p.id, p.title, pc.markdown, p.author_id, p.status, p.like_count, p.comment_count, p.view_count, p.created_at
                        """)
                .param("id", id)
                .query((rs, rowNum) -> new PostSummary(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getString("excerpt"),
                        "user-" + rs.getLong("author_id"),
                        splitTags(rs.getString("tags")),
                        rs.getLong("like_count"),
                        rs.getLong("comment_count"),
                        rs.getLong("view_count"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant()
                ))
                .single();
    }

    @Transactional
    public PostSummary create(Long authorId, CreatePostRequest request) {
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO post(author_id, title, status) VALUES (?, ?, 'PENDING')",
                    Statement.RETURN_GENERATED_KEYS
            );
            statement.setLong(1, authorId);
            statement.setString(2, request.title());
            return statement;
        }, keyHolder);
        Number generatedKey = keyHolder.getKey();
        if (generatedKey == null) {
            throw new DataAccessException("Failed to create post") {
            };
        }
        Long postId = generatedKey.longValue();
        jdbcClient.sql("INSERT INTO post_content(post_id, markdown) VALUES (:postId, :markdown)")
                .param("postId", postId)
                .param("markdown", request.content())
                .update();
        for (String tagName : request.tags()) {
            Long tagId = upsertTag(tagName);
            jdbcClient.sql("INSERT IGNORE INTO post_tag(post_id, tag_id) VALUES (:postId, :tagId)")
                    .param("postId", postId)
                    .param("tagId", tagId)
                    .update();
        }
        writeOutbox(postId, authorId, request);
        return new PostSummary(postId, request.title(), excerpt(request.content()), "user-" + authorId, request.tags(), 0, 0, 0, "PENDING", Instant.now());
    }

    private Long upsertTag(String name) {
        jdbcClient.sql("INSERT INTO tag(name, use_count) VALUES (:name, 1) ON DUPLICATE KEY UPDATE use_count = use_count + 1")
                .param("name", name)
                .update();
        return jdbcClient.sql("SELECT id FROM tag WHERE name = :name")
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private void writeOutbox(Long postId, Long authorId, CreatePostRequest request) {
        try {
            String payload = objectMapper.writeValueAsString(new PostCreatedPayload(postId, authorId, request.title(), request.tags()));
            jdbcClient.sql("""
                            INSERT INTO outbox_event(event_id, topic, aggregate_type, aggregate_id, payload)
                            VALUES (:eventId, :topic, 'POST', :aggregateId, CAST(:payload AS JSON))
                            """)
                    .param("eventId", UUID.randomUUID().toString())
                    .param("topic", EventTopics.POST_CREATED)
                    .param("aggregateId", postId.toString())
                    .param("payload", payload)
                    .update();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize post.created payload", ex);
        }
    }

    private List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return Arrays.asList(tags.split(","));
    }

    private String excerpt(String content) {
        return content.substring(0, Math.min(140, content.length()));
    }
}
