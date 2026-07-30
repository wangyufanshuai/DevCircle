package io.github.devcircle.comment.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.comment.api.CommentView;
import io.github.devcircle.comment.api.CommentThreadView;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.common.event.EventEnvelope;
import io.github.devcircle.common.event.EventTopics;
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
                .addValue("size", Math.max(1, Math.min(size, 51)));
        return jdbc.query("""
                        SELECT id, post_id, parent_id, root_id, author_id, content, status, like_count, created_at
                        FROM comment
                        WHERE post_id = :postId AND status = 'VISIBLE'
                        """ + cursor + " ORDER BY id DESC LIMIT :size", params, mapper());
    }

    public List<CommentThreadView> listThreads(Long postId, Long beforeRootId, int size, boolean includeHiddenContent) {
        String cursor = beforeRootId == null ? "" : " AND id < :beforeRootId";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("postId", postId).addValue("beforeRootId", beforeRootId)
                .addValue("includeHidden", includeHiddenContent)
                .addValue("size", Math.max(1, Math.min(size, 51)));
        List<CommentView> roots = jdbc.query("""
                        SELECT id, post_id, parent_id, root_id, author_id,
                               CASE WHEN status = 'HIDDEN' AND :includeHidden = FALSE THEN '评论已隐藏' ELSE content END AS content,
                               status, like_count, created_at
                        FROM comment
                        WHERE post_id = :postId AND parent_id IS NULL AND status IN ('VISIBLE', 'DELETED', 'HIDDEN')
                        """ + cursor + " ORDER BY id DESC LIMIT :size", params, mapper());
        if (roots.isEmpty()) return List.of();
        List<CommentThreadView> threads = new ArrayList<>(roots.size());
        for (CommentView root : roots) {
            List<CommentView> replies = jdbc.query("""
                            SELECT id, post_id, parent_id, root_id, author_id,
                                   CASE WHEN status = 'HIDDEN' AND :includeHidden = FALSE THEN '评论已隐藏' ELSE content END AS content,
                                   status, like_count, created_at
                            FROM comment
                            WHERE post_id = :postId AND root_id = :rootId AND status IN ('VISIBLE', 'DELETED', 'HIDDEN')
                            ORDER BY id ASC
                            """, Map.of("postId", postId, "rootId", root.id(), "includeHidden", includeHiddenContent), mapper());
            threads.add(new CommentThreadView(root, replies));
        }
        return threads;
    }

    @Transactional
    public CommentView create(Long userId, Long postId, Long parentId, String content, String idempotencyKey) {
        CommentView replay = replayOrClaimCreate(userId, idempotencyKey);
        if (replay != null) {
            return replay;
        }
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
        writeEvent(EventTopics.COMMENT_CREATED, commentId, postId, userId, parentId, "CREATED");
        return find(commentId);
    }

    @Transactional
    public void deleteOwn(Long commentId, Long userId, String idempotencyKey) {
        if (!claim(idempotencyKey, userId, "DELETE_COMMENT", commentId)) return;
        CommentMeta meta = meta(commentId);
        if (!userId.equals(meta.authorId()) || !"VISIBLE".equals(meta.status())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只能删除自己的可见评论");
        }
        int changed = jdbc.update("""
                        UPDATE comment SET status = 'DELETED', content = '[已删除]'
                        WHERE id = :commentId AND author_id = :userId AND status = 'VISIBLE'
                        """, Map.of("commentId", commentId, "userId", userId));
        if (changed != 1) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只能删除自己的可见评论");
        }
        writeEvent(EventTopics.COMMENT_CHANGED, commentId, meta.postId(), userId, meta.parentId(), "DELETED");
    }

    @Transactional
    public void hide(Long commentId, Long moderatorId, String idempotencyKey, String reason) {
        if (!claim(idempotencyKey, moderatorId, "HIDE_COMMENT", commentId)) return;
        CommentMeta meta = meta(commentId);
        if ("HIDDEN".equals(meta.status())) return;
        int changed = jdbc.update("UPDATE comment SET status = 'HIDDEN' WHERE id = :id AND status = 'VISIBLE'",
                Map.of("id", commentId));
        if (changed != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "评论不存在");
        }
        jdbc.update("""
                        INSERT INTO comment_audit_record(comment_id, auditor_id, action, reason)
                        VALUES (:commentId, :moderatorId, 'HIDE', :reason)
                """, Map.of("commentId", commentId, "moderatorId", moderatorId,
                "reason", reason));
        writeEvent(EventTopics.COMMENT_CHANGED, commentId, meta.postId(), moderatorId, meta.parentId(), "HIDDEN");
    }

    private CommentView replayOrClaimCreate(Long userId, String idempotencyKey) {
        int inserted = jdbc.update("""
                        INSERT IGNORE INTO idempotency_record(idempotency_key, user_id, operation)
                        VALUES (:key, :userId, 'CREATE_COMMENT')
                        """, Map.of("key", idempotencyKey, "userId", userId));
        if (inserted == 1) return null;

        IdempotencyRecord existing = jdbc.query("""
                        SELECT user_id, operation, resource_id FROM idempotency_record
                        WHERE idempotency_key = :key
                        """, Map.of("key", idempotencyKey), (rs, rowNum) -> new IdempotencyRecord(
                        rs.getLong("user_id"), rs.getString("operation"), (Long) rs.getObject("resource_id")))
                .stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.DUPLICATE_OPERATION, "Idempotency-Key 已用于其他操作"));
        if (!userId.equals(existing.userId()) || !"CREATE_COMMENT".equals(existing.operation())) {
            throw new BusinessException(ErrorCode.DUPLICATE_OPERATION, "Idempotency-Key 已用于其他操作");
        }
        if (existing.resourceId() == null) {
            throw new BusinessException(ErrorCode.DUPLICATE_OPERATION, "请求正在处理中");
        }
        return find(existing.resourceId());
    }

    private CommentMeta meta(Long commentId) {
        return jdbc.query("""
                        SELECT id, post_id, parent_id, author_id, status
                        FROM comment WHERE id = :commentId
                        """, Map.of("commentId", commentId), (rs, rowNum) -> new CommentMeta(
                        rs.getLong("id"), rs.getLong("post_id"), (Long) rs.getObject("parent_id"),
                        rs.getLong("author_id"), rs.getString("status")))
                .stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "评论不存在"));
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

    private void writeEvent(String topic, Long commentId, Long postId, Long userId, Long parentId, String changeType) {
        EventEnvelope event = EventEnvelope.now(topic, "dc-comment", String.valueOf(commentId),
                Map.of("commentId", commentId, "postId", postId, "actorId", userId,
                        "parentId", parentId == null ? 0L : parentId, "changeType", changeType));
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

    private record IdempotencyRecord(Long userId, String operation, Long resourceId) {
    }

    private record CommentMeta(Long id, Long postId, Long parentId, Long authorId, String status) {
    }
}
