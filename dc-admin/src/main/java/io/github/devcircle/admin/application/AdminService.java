package io.github.devcircle.admin.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.admin.api.AdminActionResult;
import io.github.devcircle.admin.api.AuditItem;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.common.event.EventEnvelope;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.LinkedHashMap;
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
        requireDatabase();
        try {
            return jdbcClient.sql("""
                            SELECT p.id, p.title, p.status, p.author_id
                            FROM post p
                            WHERE p.status = 'PENDING'
                            ORDER BY p.created_at ASC
                            LIMIT 50
                            """)
                    .query((rs, rowNum) -> new AuditItem(rs.getLong("id"), rs.getString("title"), rs.getString("status"), "user-" + rs.getLong("author_id")))
                    .list();
        } catch (DataAccessException ex) {
            throw dependencyUnavailable();
        }
    }

    public List<Map<String, Object>> moderationTasks(String queue, String riskLevel, String status, int page, int size) {
        requireDatabase();
        int offset = Math.max(page - 1, 0) * size;
        try {
            return jdbcClient.sql("""
                            SELECT mt.id, mt.post_id, p.title, mt.author_id, mt.queue_name, mt.machine_result,
                                   mt.risk_level, mt.risk_score, mt.reason, mt.status, mt.review_level,
                                   mt.sla_deadline, mt.created_at
                            FROM moderation_task mt
                            JOIN post p ON p.id = mt.post_id
                            WHERE (:queueName IS NULL OR mt.queue_name = :queueName)
                              AND (:riskLevel IS NULL OR mt.risk_level = :riskLevel)
                              AND (:status IS NULL OR mt.status = :status)
                            ORDER BY mt.risk_score DESC, mt.created_at ASC
                            LIMIT :size OFFSET :offset
                            """)
                    .param("queueName", blankToNull(queue))
                    .param("riskLevel", blankToNull(riskLevel))
                    .param("status", blankToNull(status))
                    .param("size", Math.max(1, Math.min(size, 100)))
                    .param("offset", offset)
                    .query((rs, rowNum) -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("taskId", rs.getLong("id"));
                        row.put("postId", rs.getLong("post_id"));
                        row.put("title", rs.getString("title"));
                        row.put("authorId", rs.getLong("author_id"));
                        row.put("queue", rs.getString("queue_name"));
                        row.put("machineResult", rs.getString("machine_result"));
                        row.put("riskLevel", rs.getString("risk_level"));
                        row.put("riskScore", rs.getDouble("risk_score"));
                        row.put("reason", rs.getString("reason"));
                        row.put("status", rs.getString("status"));
                        row.put("reviewLevel", rs.getString("review_level"));
                        row.put("slaDeadline", rs.getTimestamp("sla_deadline") == null ? null : rs.getTimestamp("sla_deadline").toInstant());
                        row.put("createdAt", rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant());
                        return row;
                    })
                    .list();
        } catch (DataAccessException ex) {
            throw dependencyUnavailable();
        }
    }

    @Transactional
    public AdminActionResult approve(Long postId, Long auditorId, String traceId, String reason) {
        return auditPost(postId, auditorId, traceId, "APPROVE_POST", "PUBLISHED", "APPROVED", EventTopics.AUDIT_COMPLETED, reason);
    }

    public AdminActionResult approve(Long postId) {
        return approve(postId, 1L, null, "审核通过");
    }

    @Transactional
    public AdminActionResult reject(Long postId, Long auditorId, String traceId, String reason) {
        return auditPost(postId, auditorId, traceId, "REJECT_POST", "REJECTED", "REJECTED", EventTopics.CONTENT_REJECTED, reason);
    }

    public AdminActionResult reject(Long postId) {
        return reject(postId, 1L, null, "审核驳回");
    }

    @Transactional
    public AdminActionResult approveModerationTask(Long taskId, Long auditorId, String traceId, String reason) {
        Long postId = postIdByTask(taskId);
        return auditPost(postId, auditorId, traceId, "APPROVE_POST", "PUBLISHED", "APPROVED", EventTopics.AUDIT_COMPLETED, reason);
    }

    @Transactional
    public AdminActionResult rejectModerationTask(Long taskId, Long auditorId, String traceId, String reason) {
        Long postId = postIdByTask(taskId);
        return auditPost(postId, auditorId, traceId, "REJECT_POST", "REJECTED", "REJECTED", EventTopics.CONTENT_REJECTED, reason);
    }

    @Transactional
    public AdminActionResult escalateModerationTask(Long taskId, Long auditorId, String traceId, String decisionReason) {
        if (decisionReason != null) {
            return updateModerationTask(taskId, auditorId, traceId, "ESCALATE_REVIEW", "ESCALATED", "ESCALATED", decisionReason);
        }
        return updateModerationTask(taskId, auditorId, traceId, "ESCALATE_REVIEW", "ESCALATED", "ESCALATED", "升级复审");
    }

    @Transactional
    public AdminActionResult requireChanges(Long taskId, Long auditorId, String traceId, String decisionReason) {
        if (decisionReason != null) {
            return updateModerationTask(taskId, auditorId, traceId, "REQUIRE_CHANGES", "CHANGES_REQUIRED", "NORMAL", decisionReason);
        }
        return updateModerationTask(taskId, auditorId, traceId, "REQUIRE_CHANGES", "CHANGES_REQUIRED", "NORMAL", "要求作者修改");
    }

    public List<Map<String, Object>> appeals(String status, int page, int size) {
        requireDatabase();
        int offset = Math.max(page - 1, 0) * size;
        try {
            return jdbcClient.sql("""
                            SELECT ar.id, ar.post_id, p.title, ar.user_id, ar.reason, ar.status, ar.created_at
                            FROM appeal_record ar
                            LEFT JOIN post p ON p.id = ar.post_id
                            WHERE (:status IS NULL OR ar.status = :status)
                            ORDER BY ar.created_at DESC
                            LIMIT :size OFFSET :offset
                            """)
                    .param("status", blankToNull(status))
                    .param("size", Math.max(1, Math.min(size, 100)))
                    .param("offset", offset)
                    .query((rs, rowNum) -> Map.<String, Object>of(
                            "appealId", rs.getLong("id"),
                            "postId", rs.getLong("post_id"),
                            "title", rs.getString("title") == null ? "" : rs.getString("title"),
                            "userId", rs.getLong("user_id"),
                            "reason", rs.getString("reason"),
                            "status", rs.getString("status"),
                            "createdAt", rs.getTimestamp("created_at").toInstant()
                    ))
                    .list();
        } catch (DataAccessException ex) {
            throw dependencyUnavailable();
        }
    }

    public List<Map<String, Object>> riskUsers(String status, int page, int size) {
        requireDatabase();
        int offset = Math.max(page - 1, 0) * size;
        try {
            return jdbcClient.sql("""
                            SELECT user_id, status, risk_score, reason, restricted_until, updated_at
                            FROM user_risk_profile
                            WHERE (:status IS NULL OR status = :status)
                            ORDER BY risk_score DESC, updated_at DESC
                            LIMIT :size OFFSET :offset
                            """)
                    .param("status", blankToNull(status))
                    .param("size", Math.max(1, Math.min(size, 100)))
                    .param("offset", offset)
                    .query((rs, rowNum) -> Map.<String, Object>of(
                            "userId", rs.getLong("user_id"),
                            "status", rs.getString("status"),
                            "riskScore", rs.getDouble("risk_score"),
                            "reason", rs.getString("reason"),
                            "restrictedUntil", rs.getTimestamp("restricted_until") == null ? "" : rs.getTimestamp("restricted_until").toInstant(),
                            "updatedAt", rs.getTimestamp("updated_at").toInstant()
                    ))
                    .list();
        } catch (DataAccessException ex) {
            throw dependencyUnavailable();
        }
    }

    public Map<String, Object> riskProfile(Long userId) {
        requireDatabase();
        try {
            Map<String, Object> profile = jdbcClient.sql("SELECT user_id, status, risk_score, reason, updated_at FROM user_risk_profile WHERE user_id = :userId")
                    .param("userId", userId)
                    .query((rs, rowNum) -> Map.<String, Object>of(
                            "userId", rs.getLong("user_id"),
                            "status", rs.getString("status"),
                            "riskScore", rs.getDouble("risk_score"),
                            "reason", rs.getString("reason"),
                            "updatedAt", rs.getTimestamp("updated_at").toInstant()
                    ))
                    .optional()
                    .orElse(Map.of("userId", userId, "status", "NORMAL", "riskScore", 0, "reason", ""));
            List<Map<String, Object>> signals = jdbcClient.sql("""
                            SELECT signal_type, risk_score, description, created_at
                            FROM content_risk_signal
                            WHERE user_id = :userId
                            ORDER BY created_at DESC
                            LIMIT 20
                            """)
                    .param("userId", userId)
                    .query((rs, rowNum) -> Map.<String, Object>of(
                            "signalType", rs.getString("signal_type"),
                            "riskScore", rs.getDouble("risk_score"),
                            "description", rs.getString("description"),
                            "createdAt", rs.getTimestamp("created_at").toInstant()
                    ))
                    .list();
            return Map.of("profile", profile, "signals", signals);
        } catch (DataAccessException ex) {
            throw dependencyUnavailable();
        }
    }

    public AdminActionResult restrictUser(Long userId, String status) {
        requireDatabase();
        jdbcClient.sql("""
                            INSERT INTO user_risk_profile(user_id, status, risk_score, reason, restricted_until)
                            VALUES (:userId, :status, 90, :reason, DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 7 DAY))
                            ON DUPLICATE KEY UPDATE status = VALUES(status), risk_score = VALUES(risk_score), reason = VALUES(reason), restricted_until = VALUES(restricted_until)
                            """)
                    .param("userId", userId)
                    .param("status", status == null || status.isBlank() ? "NO_POST" : status)
                    .param("reason", "管理员手动治理")
                .update();
        logAudit(1L, "RESTRICT_USER", "USER", String.valueOf(userId), null, null, Map.of("status", status));
        return new AdminActionResult("RESTRICT_USER", Map.of("userId", userId, "status", status == null ? "NO_POST" : status));
    }

    public AdminActionResult unrestrictUser(Long userId) {
        requireDatabase();
        jdbcClient.sql("""
                            INSERT INTO user_risk_profile(user_id, status, risk_score, reason, restricted_until)
                            VALUES (:userId, 'NORMAL', 0, '', NULL)
                            ON DUPLICATE KEY UPDATE status = 'NORMAL', risk_score = 0, reason = '', restricted_until = NULL
                            """)
                    .param("userId", userId)
                .update();
        logAudit(1L, "UNRESTRICT_USER", "USER", String.valueOf(userId), null, null, Map.of());
        return new AdminActionResult("UNRESTRICT_USER", Map.of("userId", userId, "status", "NORMAL"));
    }

    public AdminActionResult ban(Long userId) {
        return restrictUser(userId, "BANNED");
    }

    public AdminActionResult createTag() {
        requireDatabase();
        try {
            jdbcClient.sql("INSERT INTO tag(name, use_count) VALUES ('MysticVerse精选', 1) ON DUPLICATE KEY UPDATE use_count = use_count + 1").update();
            Long tagId = jdbcClient.sql("SELECT id FROM tag WHERE name = 'MysticVerse精选'").query(Long.class).single();
            return new AdminActionResult("CREATE_TAG", Map.of("tagId", tagId, "name", "MysticVerse精选"));
        } catch (DataAccessException ex) {
            throw dependencyUnavailable();
        }
    }

    public Map<String, Object> overview() {
        requireDatabase();
        try {
            Long posts = oneLong("SELECT COUNT(*) FROM post");
            Long pending = oneLong("SELECT COUNT(*) FROM moderation_task WHERE status = 'PENDING'");
            Long approved = oneLong("SELECT COUNT(*) FROM moderation_decision WHERE action = 'APPROVE_POST'");
            Long rejected = oneLong("SELECT COUNT(*) FROM moderation_decision WHERE action = 'REJECT_POST'");
            Long impressions = oneLong("SELECT COUNT(*) FROM recommendation_impression");
            Long recEvents = oneLong("SELECT COUNT(*) FROM recommend_event_log");
            Long riskUsers = oneLong("SELECT COUNT(*) FROM user_risk_profile WHERE status <> 'NORMAL'");
            double passRate = (approved + rejected) == 0 ? 0 : approved * 1.0 / (approved + rejected);
            return Map.of(
                    "posts", posts,
                    "pendingAudits", pending,
                    "auditPassRate", passRate,
                    "recommendImpressions", impressions,
                    "recommendEvents", recEvents,
                    "riskUsers", riskUsers
            );
        } catch (DataAccessException ex) {
            throw dependencyUnavailable();
        }
    }

    public List<Map<String, Object>> themeMetrics() {
        requireDatabase();
        try {
            return jdbcClient.sql("""
                            SELECT theme,
                                   COUNT(*) AS posts,
                                   AVG(like_count) AS avg_likes,
                                   AVG(view_count) AS avg_views,
                                   SUM(solved_count) AS solves
                            FROM post
                            GROUP BY theme
                            """)
                    .query((rs, rowNum) -> Map.<String, Object>of(
                            "theme", rs.getString("theme"),
                            "posts", rs.getLong("posts"),
                            "avgLikes", rs.getDouble("avg_likes"),
                            "avgViews", rs.getDouble("avg_views"),
                            "solves", rs.getLong("solves")
                    ))
                    .list();
        } catch (DataAccessException ex) {
            throw dependencyUnavailable();
        }
    }

    public List<Map<String, Object>> experiments() {
        requireDatabase();
        try {
            return jdbcClient.sql("""
                            SELECT experiment_id, name, scene, status, traffic_percent, weights, updated_at
                            FROM recommend_experiment
                            ORDER BY updated_at DESC
                            """)
                    .query((rs, rowNum) -> Map.<String, Object>of(
                            "experimentId", rs.getString("experiment_id"),
                            "name", rs.getString("name"),
                            "scene", rs.getString("scene"),
                            "status", rs.getString("status"),
                            "trafficPercent", rs.getInt("traffic_percent"),
                            "weights", rs.getString("weights"),
                            "updatedAt", rs.getTimestamp("updated_at").toInstant()
                    ))
                    .list();
        } catch (DataAccessException ex) {
            throw dependencyUnavailable();
        }
    }

    public AdminActionResult createExperiment(Map<String, Object> payload) {
        String experimentId = String.valueOf(payload.getOrDefault("experimentId", "exp-manual-" + System.currentTimeMillis()));
        String name = String.valueOf(payload.getOrDefault("name", "手动推荐实验"));
        String scene = String.valueOf(payload.getOrDefault("scene", "home"));
        requireDatabase();
        try {
            jdbcClient.sql("""
                                INSERT INTO recommend_experiment(experiment_id, name, scene, status, traffic_percent, weights)
                                VALUES (:experimentId, :name, :scene, 'DRAFT', 0, CAST(:weights AS JSON))
                                ON DUPLICATE KEY UPDATE name = VALUES(name), scene = VALUES(scene)
                                """)
                        .param("experimentId", experimentId)
                        .param("name", name)
                        .param("scene", scene)
                        .param("weights", objectMapper.writeValueAsString(payload.getOrDefault("weights", Map.of("theme", 1.0))))
                    .update();
        } catch (DataAccessException | JsonProcessingException ex) {
            throw dependencyUnavailable();
        }
        return new AdminActionResult("CREATE_RECOMMEND_EXPERIMENT", Map.of("experimentId", experimentId, "scene", scene, "status", "DRAFT"));
    }

    public AdminActionResult updateExperimentStatus(String experimentId, String status) {
        requireDatabase();
        int changed = jdbcClient.sql("UPDATE recommend_experiment SET status = :status, traffic_percent = CASE WHEN :status = 'ACTIVE' THEN 100 ELSE traffic_percent END WHERE experiment_id = :experimentId")
                    .param("status", status)
                    .param("experimentId", experimentId)
                .update();
        if (changed != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "推荐实验不存在");
        }
        return new AdminActionResult("UPDATE_RECOMMEND_EXPERIMENT", Map.of("experimentId", experimentId, "status", status));
    }

    public List<Map<String, Object>> auditLogs(int page, int size, String action) {
        requireDatabase();
        int offset = Math.max(page - 1, 0) * size;
        try {
            return jdbcClient.sql("""
                            SELECT id, user_id, action, target_type, target_id, ip, trace_id, created_at
                            FROM audit_log
                            WHERE (:action IS NULL OR action = :action)
                            ORDER BY created_at DESC
                            LIMIT :size OFFSET :offset
                            """)
                    .param("action", blankToNull(action))
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
            throw dependencyUnavailable();
        }
    }

    public Map<String, Object> opsHealth() {
        if (jdbcClient == null) {
            return Map.of("database", "DOWN", "source", "HEALTH_CHECK", "degraded", true);
        }
        try {
            Long posts = oneLong("SELECT COUNT(*) FROM post");
            Long outboxNew = oneLong("SELECT COUNT(*) FROM outbox_event WHERE status = 'NEW'");
            Long moderationPending = oneLong("SELECT COUNT(*) FROM moderation_task WHERE status = 'PENDING'");
            Long riskUsers = oneLong("SELECT COUNT(*) FROM user_risk_profile WHERE status <> 'NORMAL'");
            return Map.of(
                    "database", "UP",
                    "outboxBacklog", outboxNew,
                    "pendingModeration", moderationPending,
                    "riskUsers", riskUsers,
                    "posts", posts,
                    "source", "DB"
            );
        } catch (DataAccessException ex) {
            return Map.of("database", "DOWN", "source", "HEALTH_CHECK", "degraded", true);
        }
    }

    public List<Map<String, Object>> outboxEvents(String status, String topic, int page, int size) {
        requireDatabase();
        int offset = Math.max(page - 1, 0) * size;
        try {
            return jdbcClient.sql("""
                            SELECT id, event_id, topic, aggregate_type, aggregate_id, status, created_at, published_at
                            FROM outbox_event
                            WHERE (:status IS NULL OR status = :status)
                              AND (:topic IS NULL OR topic = :topic)
                            ORDER BY created_at DESC
                            LIMIT :size OFFSET :offset
                            """)
                    .param("status", blankToNull(status))
                    .param("topic", blankToNull(topic))
                    .param("size", Math.max(1, Math.min(size, 200)))
                    .param("offset", offset)
                    .query((rs, rowNum) -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", rs.getLong("id"));
                        row.put("eventId", rs.getString("event_id"));
                        row.put("topic", rs.getString("topic"));
                        row.put("aggregateType", rs.getString("aggregate_type"));
                        row.put("aggregateId", rs.getString("aggregate_id"));
                        row.put("status", rs.getString("status"));
                        row.put("createdAt", rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant());
                        row.put("publishedAt", rs.getTimestamp("published_at") == null ? null : rs.getTimestamp("published_at").toInstant());
                        return row;
                    })
                    .list();
        } catch (DataAccessException ex) {
            throw dependencyUnavailable();
        }
    }

    public AdminActionResult replayOutbox(String topic, int batchSize) {
        requireDatabase();
        try {
            int affected = jdbcClient.sql("""
                            UPDATE outbox_event
                            SET status = 'NEW', published_at = NULL
                            WHERE status <> 'NEW'
                              AND (:topic IS NULL OR topic = :topic)
                            ORDER BY created_at ASC
                            LIMIT :batchSize
                            """)
                    .param("topic", blankToNull(topic))
                    .param("batchSize", Math.max(1, Math.min(batchSize, 500)))
                    .update();
            logAudit(1L, "REPLAY_OUTBOX", "OUTBOX", topic == null ? "*" : topic, null, null, Map.of("affected", affected));
            return new AdminActionResult("REPLAY_OUTBOX", Map.of("affected", affected, "topic", topic == null ? "*" : topic));
        } catch (DataAccessException ex) {
            throw dependencyUnavailable();
        }
    }

    public Map<String, Object> degradation() {
        if (jdbcClient == null) {
            return Map.of("degraded", true, "source", "HEALTH_CHECK", "reason", "DATABASE_UNAVAILABLE");
        }
        try {
            Long outboxBacklog = oneLong("SELECT COUNT(*) FROM outbox_event WHERE status = 'NEW'");
            Long impressions = oneLong("SELECT COUNT(*) FROM recommendation_impression");
            Long events = oneLong("SELECT COUNT(*) FROM recommend_event_log");
            Long moderationPending = oneLong("SELECT COUNT(*) FROM moderation_task WHERE status = 'PENDING'");
            return Map.of(
                    "degraded", false,
                    "source", "DB",
                    "outboxBacklog", outboxBacklog,
                    "recommendImpressions", impressions,
                    "recommendEvents", events,
                    "pendingModeration", moderationPending
            );
        } catch (DataAccessException ex) {
            return Map.of("degraded", true, "source", "HEALTH_CHECK", "reason", "DATABASE_UNAVAILABLE");
        }
    }

    private AdminActionResult auditPost(Long postId, Long auditorId, String traceId, String action, String postStatus, String taskStatus, String topic, String reason) {
        requireDatabase();
        requireReason(reason);
        try {
            int postRows = jdbcClient.sql("UPDATE post SET status = :status, review_reason = :reason WHERE id = :postId AND status = 'PENDING'")
                    .param("status", postStatus)
                    .param("reason", reason.trim())
                    .param("postId", postId)
                    .update();
            if (postRows == 0) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "待审核内容不存在或状态已变化");
            }
            int taskRows = jdbcClient.sql("""
                            UPDATE moderation_task
                            SET status = :taskStatus, reviewer_id = :auditorId, reviewed_at = CURRENT_TIMESTAMP
                            WHERE post_id = :postId AND status IN ('PENDING', 'ESCALATED')
                            """)
                    .param("taskStatus", taskStatus)
                    .param("auditorId", auditorId)
                    .param("postId", postId)
                    .update();
            if (taskRows == 0) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "审核任务不存在或已处理");
            }
            Long taskId = jdbcClient.sql("SELECT COALESCE(MAX(id), 0) FROM moderation_task WHERE post_id = :postId")
                    .param("postId", postId)
                    .query(Long.class)
                    .single();
            jdbcClient.sql("INSERT INTO post_audit_record(post_id, auditor_id, action, reason) VALUES (:postId, :auditorId, :action, :reason)")
                    .param("postId", postId)
                    .param("auditorId", auditorId)
                    .param("action", action)
                    .param("reason", reason.trim())
                    .update();
            jdbcClient.sql("INSERT INTO moderation_decision(task_id, post_id, reviewer_id, action, reason) VALUES (:taskId, :postId, :auditorId, :action, :reason)")
                    .param("taskId", taskId)
                    .param("postId", postId)
                    .param("auditorId", auditorId)
                    .param("action", action)
                    .param("reason", reason.trim())
                    .update();
            if ("PUBLISHED".equals(postStatus)) {
                jdbcClient.sql("""
                                UPDATE media_asset m
                                JOIN post_media pm ON pm.media_id = m.id
                                SET m.status = 'APPROVED'
                                WHERE pm.post_id = :postId AND m.status = 'PENDING_REVIEW'
                                """)
                        .param("postId", postId)
                        .update();
            }
            logAudit(auditorId, action, "POST", String.valueOf(postId), null, traceId, Map.of("status", postStatus));
            writeOutbox(postId, action, postStatus, topic);
            return new AdminActionResult(action, Map.of("postId", postId, "status", postStatus, "event", topic));
        } catch (DataAccessException ex) {
            throw ex;
        }
    }

    private AdminActionResult updateModerationTask(Long taskId, Long auditorId, String traceId, String action, String status, String reviewLevel, String reason) {
        requireDatabase();
        requireReason(reason);
        try {
            Long postId = jdbcClient.sql("SELECT post_id FROM moderation_task WHERE id = :taskId")
                    .param("taskId", taskId)
                    .query(Long.class)
                    .single();
            int updated = jdbcClient.sql("""
                            UPDATE moderation_task
                            SET status = :status, review_level = :reviewLevel, reviewer_id = :auditorId, reviewed_at = CURRENT_TIMESTAMP
                            WHERE id = :taskId AND status IN ('PENDING', 'ESCALATED')
                            """)
                    .param("status", status)
                    .param("reviewLevel", reviewLevel)
                    .param("auditorId", auditorId)
                    .param("taskId", taskId)
                    .update();
            if (updated == 0) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "审核任务不存在或已处理");
            }
            jdbcClient.sql("INSERT INTO moderation_decision(task_id, post_id, reviewer_id, action, reason) VALUES (:taskId, :postId, :auditorId, :action, :reason)")
                    .param("taskId", taskId)
                    .param("postId", postId)
                    .param("auditorId", auditorId)
                    .param("action", action)
                    .param("reason", reason)
                    .update();
            if ("CHANGES_REQUIRED".equals(status)) {
                jdbcClient.sql("UPDATE post SET status = 'CHANGES_REQUIRED', review_reason = :reason WHERE id = :postId AND status = 'PENDING'")
                        .param("reason", reason.trim())
                        .param("postId", postId)
                        .update();
                writeOutbox(postId, action, "CHANGES_REQUIRED", EventTopics.CONTENT_REJECTED);
            }
            logAudit(auditorId, action, "MODERATION_TASK", String.valueOf(taskId), null, traceId, Map.of("postId", postId, "status", status));
            return new AdminActionResult(action, Map.of("taskId", taskId, "postId", postId, "status", status));
        } catch (DataAccessException ex) {
            throw ex;
        }
    }

    private Long postIdByTask(Long taskId) {
        requireDatabase();
        try {
            return jdbcClient.sql("SELECT post_id FROM moderation_task WHERE id = :taskId")
                    .param("taskId", taskId)
                    .query(Long.class)
                    .optional()
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "审核任务不存在"));
        } catch (DataAccessException ex) {
            throw ex;
        }
    }

    private void logAudit(Long userId, String action, String targetType, String targetId, String ip, String traceId, Map<String, Object> metadata) {
        requireDatabase();
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
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize audit metadata", ex);
        }
    }

    private void writeOutbox(Long postId, String action, String status, String topic) {
        requireDatabase();
        try {
            Long receiverId = jdbcClient.sql("SELECT author_id FROM post WHERE id = :postId")
                    .param("postId", postId)
                    .query(Long.class)
                    .single();
            EventEnvelope event = EventEnvelope.now(topic, "dc-content", String.valueOf(postId),
                    Map.of("postId", postId, "receiverId", receiverId, "action", action, "status", status));
            String payload = objectMapper.writeValueAsString(event);
            jdbcClient.sql("""
                            INSERT INTO outbox_event(event_id, topic, aggregate_type, aggregate_id, payload)
                            VALUES (:eventId, :topic, 'POST_AUDIT', :aggregateId, CAST(:payload AS JSON))
                            """)
                    .param("eventId", event.eventId().toString())
                    .param("topic", event.type())
                    .param("aggregateId", String.valueOf(postId))
                    .param("payload", payload)
                    .update();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize moderation event", ex);
        }
    }

    private void requireDatabase() {
        if (jdbcClient == null) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "审核数据服务暂时不可用");
        }
    }

    private BusinessException dependencyUnavailable() {
        return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "运营数据暂时不可用");
    }

    private void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "审核操作必须填写理由");
        }
        if (reason.length() > 512) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "审核理由不能超过 512 个字符");
        }
    }

    private Long oneLong(String sql) {
        Long value = jdbcClient.sql(sql).query(Long.class).single();
        return value == null ? 0 : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

}
