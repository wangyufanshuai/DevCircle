package io.github.devcircle.comment.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.comment.api.CommentView;
import io.github.devcircle.comment.api.CommentThreadView;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.common.event.EventEnvelope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

@Repository
public class CommentRepository {
    private static final String COMMENT_CREATED = "comment.created";
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public CommentRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.objectMapper = objectMapper;
    }

    public List<CommentView> list(Long postId, Long beforeId, int size) {
        String cursor = beforeId == null ? "" : " AND id < :beforeId";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("postId", postId)
                .addValue("beforeId", beforeId)
                .addValue("size", Math.max(1, Math.min(size, 50)));
        return jdbc.query("""
                        SELECT id, post_id, parent_id, root_id, author_id, content, status, like_count, created_at
                        FROM comment
                        WHERE post_id = :postId AND status = 'VISIBLE'
                        """ + cursor + " ORDER BY id DESC LIMIT :size", params, mapper());
    }

    public List<CommentThreadView> listThreads(Long postId, Long beforeRootId, int size) {
        String cursor = beforeRootId == null ? "" : " AND id < :beforeRootId";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("postId", postId).addValue("beforeRootId", beforeRootId)
                .addValue("size", Math.max(1, Math.min(size, 50)));
        List<CommentView> roots = jdbc.query("""
                        SELECT id, post_id, parent_id, root_id, author_id, content, status, like_count, created_at
                        FROM comment
                        WHERE post_id = :postId AND parent_id IS NULL AND status IN ('VISIBLE', 'DELETED')
                        """ + cursor + " ORDER BY id DESC LIMIT :size", params, mapper());
        if (roots.isEmpty()) return List.of();
        List<CommentThreadView> threads = new ArrayList<>(roots.size());
        for (CommentView root : roots) {
            List<CommentView> replies = jdbc.query("""
                            SELECT id, post_id, parent_id, root_id, author_id, content, status, like_count, created_at
                            FROM comment
                            WHERE post_id = :postId AND root_id = :rootId AND status IN ('VISIBLE', 'DELETED')
                            ORDER BY id ASC
                            """, Map.of("postId", postId, "rootId", root.id()), mapper());
            threads.add(new CommentThreadView(root, replies));
        }
        return threads;
    }

    @Transactional
    public CommentView create(Long userId, Long postId, Long parentId, String content, String idempotencyKey) {
        Long rootId = null;
        if (parentId != null) {
            Parent parent = jdbc.query("""
                            SELECT id, post_id, parent_id, root_id, status FROM comment WHERE id = :parentId
                            """, Map.of("parentId", parentId), (rs, rowNum) -> new Parent(
                    rs.getLong("id"), rs.getLong("post_id"), (Long) rs.getObject("parent_id"),
                    (Long) rs.getObject("root_id"), rs.getString("status")))
                    .stream().findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "回复的评论不存在"));
            if (!parent.postId().equals(postId) || !"VISIBLE".equals(parent.status())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不能回复该评论");
            }
            if (parent.parentId() != null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "评论最多支持两级回复");
            }
            rootId = parent.rootId() == null ? parent.id() : parent.rootId();
        }
        MapSqlParameterSource idempotency = new MapSqlParameterSource()
                .addValue("key", idempotencyKey).addValue("userId", userId)
                .addValue("operation", "CREATE_COMMENT");
        int claimed = jdbc.update("""
                        INSERT IGNORE INTO idempotency_record(idempotency_key, user_id, operation)
                        VALUES (:key, :userId, :operation)
                        """, idempotency);
        if (claimed == 0) {
            Long existingId = jdbc.queryForObject("""
                            SELECT resource_id FROM idempotency_record
                            WHERE idempotency_key = :key AND user_id = :userId AND operation = :operation
                            """, idempotency, Long.class);
            if (existingId == null) {
                throw new BusinessException(ErrorCode.DUPLICATE_OPERATION, "请求正在处理中");
            }
            return find(existingId);
        }
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        MapSqlParameterSource insert = new MapSqlParameterSource()
                .addValue("postId", postId).addValue("authorId", userId)
                .addValue("parentId", parentId).addValue("rootId", rootId)
                .addValue("content", content.trim());
        jdbc.update("""
                        INSERT INTO comment(post_id, author_id, parent_id, root_id, content, status)
                        VALUES (:postId, :authorId, :parentId, :rootId, :content, 'VISIBLE')
                        """, insert, keyHolder, new String[]{"id"});
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Failed to create comment");
        }
        Long commentId = key.longValue();
        jdbc.update("UPDATE idempotency_record SET resource_id = :commentId WHERE idempotency_key = :key",
                Map.of("commentId", commentId, "key", idempotencyKey));
        writeEvent(commentId, postId, userId, parentId);
        return find(commentId);
    }

    @Transactional
    public void deleteOwn(Long commentId, Long userId, String idempotencyKey) {
        if (!claim(idempotencyKey, userId, "DELETE_COMMENT", commentId)) return;
        int changed = jdbc.update("""
                        UPDATE comment SET status = 'DELETED', content = '[已删除]'
                        WHERE id = :commentId AND author_id = :userId AND status = 'VISIBLE'
                        """, Map.of("commentId", commentId, "userId", userId));
        if (changed != 1) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只能删除自己的可见评论");
        }
    }

    @Transactional
    public void hide(Long commentId, Long moderatorId, String idempotencyKey, String reason) {
        if (!claim(idempotencyKey, moderatorId, "HIDE_COMMENT", commentId)) return;
        int changed = jdbc.update("UPDATE comment SET status = 'HIDDEN' WHERE id = :id AND status <> 'DELETED'",
                Map.of("id", commentId));
        if (changed != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "评论不存在");
        }
        jdbc.update("""
                        INSERT INTO comment_audit_record(comment_id, auditor_id, action, reason)
                        VALUES (:commentId, :moderatorId, 'HIDE', :reason)
                        """, Map.of("commentId", commentId, "moderatorId", moderatorId,
                "reason", reason == null ? "违规内容" : reason));
    }

    private CommentView find(Long id) {
        return jdbc.query("""
                        SELECT id, post_id, parent_id, root_id, author_id, content, status, like_count, created_at
                        FROM comment WHERE id = :id
                        """, Map.of("id", id), mapper()).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "评论不存在"));
    }

    private RowMapper<CommentView> mapper() {
        return (rs, rowNum) -> {
            Long authorId = rs.getLong("author_id");
            Timestamp createdAt = rs.getTimestamp("created_at");
            return new CommentView(rs.getLong("id"), rs.getLong("post_id"),
                    (Long) rs.getObject("parent_id"), (Long) rs.getObject("root_id"), authorId,
                    "用户 " + authorId, rs.getString("content"), rs.getString("status"),
                    rs.getLong("like_count"), createdAt.toInstant());
        };
    }

    private void writeEvent(Long commentId, Long postId, Long userId, Long parentId) {
        EventEnvelope event = EventEnvelope.now(COMMENT_CREATED, "dc-comment", String.valueOf(commentId),
                Map.of("commentId", commentId, "postId", postId, "actorId", userId,
                        "parentId", parentId == null ? 0L : parentId));
        try {
            jdbc.update("""
                            INSERT INTO outbox_event(event_id, topic, aggregate_type, aggregate_id, payload)
                            VALUES (:eventId, :topic, 'COMMENT', :aggregateId, CAST(:payload AS JSON))
                            """, Map.of("eventId", event.eventId().toString(), "topic", event.type(),
                    "aggregateId", event.aggregateId(), "payload", objectMapper.writeValueAsString(event)));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize comment event", ex);
        }
    }

    private boolean claim(String key, Long userId, String operation, Long resourceId) {
        int inserted = jdbc.update("""
                INSERT IGNORE INTO idempotency_record(idempotency_key, user_id, operation, resource_id)
                VALUES (:key, :userId, :operation, :resourceId)
                """, Map.of("key", key, "userId", userId, "operation", operation, "resourceId", resourceId));
        if (inserted > 0) return true;
        Map<String, Object> existing = jdbc.query("SELECT user_id, operation, resource_id FROM idempotency_record WHERE idempotency_key = :key",
                Map.of("key", key), rs -> rs.next() ? Map.of("userId", rs.getLong("user_id"), "operation", rs.getString("operation"), "resourceId", rs.getLong("resource_id")) : Map.of());
        if (existing.isEmpty() || !userId.equals(existing.get("userId")) || !operation.equals(existing.get("operation")) || !resourceId.equals(existing.get("resourceId"))) {
            throw new BusinessException(ErrorCode.DUPLICATE_OPERATION, "Idempotency-Key 已用于其他操作");
        }
        return false;
    }

    private record Parent(Long id, Long postId, Long parentId, Long rootId, String status) {
    }
}
