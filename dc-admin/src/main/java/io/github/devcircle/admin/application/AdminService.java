package io.github.devcircle.admin.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.admin.api.AdminActionResult;
import io.github.devcircle.admin.api.AuditItem;
import io.github.devcircle.common.event.EventTopics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AdminService {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public AdminService() {
        this.jdbcClient = null;
        this.objectMapper = new ObjectMapper();
    }

    @Autowired
    public AdminService(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public List<AuditItem> pendingPosts() {
        if (jdbcClient == null) {
            return fallbackPendingPosts();
        }
        try {
            return jdbcClient.sql("""
                            SELECT p.id, p.title, p.status, p.author_id
                            FROM post p
                            WHERE p.status = 'PENDING'
                            ORDER BY p.created_at ASC
                            LIMIT 50
                            """)
                    .query((rs, rowNum) -> new AuditItem(
                            rs.getLong("id"),
                            rs.getString("title"),
                            rs.getString("status"),
                            "user-" + rs.getLong("author_id")
                    ))
                    .list();
        } catch (DataAccessException ex) {
            return fallbackPendingPosts();
        }
    }

    public AdminActionResult approve(Long postId) {
        return approve(postId, 1L, null);
    }

    @Transactional
    public AdminActionResult approve(Long postId, Long auditorId, String traceId) {
        return auditPost(postId, auditorId, traceId, "APPROVE_POST", "PUBLISHED");
    }

    public AdminActionResult reject(Long postId) {
        return reject(postId, 1L, null);
    }

    @Transactional
    public AdminActionResult reject(Long postId, Long auditorId, String traceId) {
        return auditPost(postId, auditorId, traceId, "REJECT_POST", "REJECTED");
    }

    public AdminActionResult ban(Long userId) {
        logAudit(1L, "BAN_USER", "USER", String.valueOf(userId), null, null, Map.of("status", "BANNED"));
        return new AdminActionResult("BAN_USER", Map.of("userId", userId, "status", "BANNED"));
    }

    public AdminActionResult createTag() {
        if (jdbcClient == null) {
            return new AdminActionResult("CREATE_TAG", Map.of("tagId", 88, "name", "MysticVerse精选", "degraded", true));
        }
        try {
            jdbcClient.sql("INSERT INTO tag(name, use_count) VALUES ('MysticVerse精选', 1) ON DUPLICATE KEY UPDATE use_count = use_count + 1").update();
            Long tagId = jdbcClient.sql("SELECT id FROM tag WHERE name = 'MysticVerse精选'").query(Long.class).single();
            return new AdminActionResult("CREATE_TAG", Map.of("tagId", tagId, "name", "MysticVerse精选"));
        } catch (DataAccessException ex) {
            return new AdminActionResult("CREATE_TAG", Map.of("tagId", 88, "name", "MysticVerse精选", "degraded", true));
        }
    }

    public Map<String, Object> overview() {
        if (jdbcClient == null) {
            return fallbackOverview();
        }
        try {
            Long posts = jdbcClient.sql("SELECT COUNT(*) FROM post").query(Long.class).single();
            Long pending = jdbcClient.sql("SELECT COUNT(*) FROM post WHERE status = 'PENDING'").query(Long.class).single();
            Long published = jdbcClient.sql("SELECT COUNT(*) FROM post WHERE status = 'PUBLISHED'").query(Long.class).single();
            Long audits = jdbcClient.sql("SELECT COUNT(*) FROM post_audit_record").query(Long.class).single();
            return Map.of("posts", value(posts), "publishedPosts", value(published), "pendingAudits", value(pending), "auditRecords", value(audits));
        } catch (DataAccessException ex) {
            return fallbackOverview();
        }
    }

    public List<Map<String, Object>> auditLogs(int page, int size, String action) {
        if (jdbcClient == null) {
            return List.of(Map.of("action", "DEGRADED_AUDIT_LOG", "targetType", "SYSTEM", "targetId", "fallback"));
        }
        int offset = Math.max(page - 1, 0) * size;
        try {
            return jdbcClient.sql("""
                            SELECT id, user_id, action, target_type, target_id, ip, trace_id, created_at
                            FROM audit_log
                            WHERE (:action IS NULL OR action = :action)
                            ORDER BY created_at DESC
                            LIMIT :size OFFSET :offset
                            """)
                    .param("action", action == null || action.isBlank() ? null : action)
                    .param("size", Math.max(1, Math.min(size, 100)))
                    .param("offset", offset)
                    .query((rs, rowNum) -> Map.<String, Object>of(
                            "id", rs.getLong("id"),
                            "userId", rs.getLong("user_id"),
                            "action", rs.getString("action"),
                            "targetType", rs.getString("target_type"),
                            "targetId", rs.getString("target_id"),
                            "ip", rs.getString("ip") == null ? "" : rs.getString("ip"),
                            "traceId", rs.getString("trace_id") == null ? "" : rs.getString("trace_id"),
                            "createdAt", rs.getTimestamp("created_at").toInstant()
                    ))
                    .list();
        } catch (DataAccessException ex) {
            return List.of(Map.of("action", "DEGRADED_AUDIT_LOG", "targetType", "SYSTEM", "targetId", "fallback"));
        }
    }

    private AdminActionResult auditPost(Long postId, Long auditorId, String traceId, String action, String status) {
        if (jdbcClient == null) {
            return new AdminActionResult(action, Map.of("postId", postId, "status", status, "event", "audit.completed", "degraded", true));
        }
        try {
            jdbcClient.sql("UPDATE post SET status = :status WHERE id = :postId")
                    .param("status", status)
                    .param("postId", postId)
                    .update();
            jdbcClient.sql("INSERT INTO post_audit_record(post_id, auditor_id, action, reason) VALUES (:postId, :auditorId, :action, :reason)")
                    .param("postId", postId)
                    .param("auditorId", auditorId)
                    .param("action", action)
                    .param("reason", "v1.0 admin moderation")
                    .update();
            logAudit(auditorId, action, "POST", String.valueOf(postId), null, traceId, Map.of("status", status));
            writeAuditOutbox(postId, action, status);
            return new AdminActionResult(action, Map.of("postId", postId, "status", status, "event", "audit.completed"));
        } catch (DataAccessException ex) {
            return new AdminActionResult(action, Map.of("postId", postId, "status", status, "event", "audit.completed", "degraded", true));
        }
    }

    private void logAudit(Long userId, String action, String targetType, String targetId, String ip, String traceId, Map<String, Object> metadata) {
        if (jdbcClient == null) {
            return;
        }
        try {
            jdbcClient.sql("""
                            INSERT INTO audit_log(user_id, action, target_type, target_id, ip, trace_id, metadata)
                            VALUES (:userId, :action, :targetType, :targetId, :ip, :traceId, CAST(:metadata AS JSON))
                            """)
                    .param("userId", userId)
                    .param("action", action)
                    .param("targetType", targetType)
                    .param("targetId", targetId)
                    .param("ip", ip)
                    .param("traceId", traceId)
                    .param("metadata", objectMapper.writeValueAsString(metadata))
                    .update();
        } catch (DataAccessException | JsonProcessingException ignored) {
        }
    }

    private void writeAuditOutbox(Long postId, String action, String status) {
        if (jdbcClient == null) {
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(Map.of("postId", postId, "action", action, "status", status));
            jdbcClient.sql("""
                            INSERT INTO outbox_event(event_id, topic, aggregate_type, aggregate_id, payload)
                            VALUES (:eventId, :topic, 'POST_AUDIT', :aggregateId, CAST(:payload AS JSON))
                            """)
                    .param("eventId", UUID.randomUUID().toString())
                    .param("topic", EventTopics.AUDIT_COMPLETED)
                    .param("aggregateId", String.valueOf(postId))
                    .param("payload", payload)
                    .update();
        } catch (DataAccessException | JsonProcessingException ignored) {
        }
    }

    private long value(Long value) {
        return value == null ? 0 : value;
    }

    private List<AuditItem> fallbackPendingPosts() {
        return List.of(
                new AuditItem(1002L, "雾港钟楼的第十三声", "PENDING", "林墨"),
                new AuditItem(1043L, "土卫六雨季城市设定稿", "PENDING", "秦川")
        );
    }

    private Map<String, Object> fallbackOverview() {
        return Map.of("users", 12890, "posts", 120, "pendingAudits", 18, "searchQps", 42.7, "degraded", true);
    }
}
