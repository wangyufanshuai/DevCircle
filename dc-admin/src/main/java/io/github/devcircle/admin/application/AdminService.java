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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class AdminService {
    private static final String LEGACY_REQUEST_FINGERPRINT = "0".repeat(64);
    private static final Set<String> RESTRICTION_STATUSES = Set.of("WATCH", "RATE_LIMITED", "NO_POST", "BANNED");
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

    private AdminActionResult approveInternal(Long postId, Long auditorId, String traceId, String reason) {
        return auditPost(postId, null, auditorId, traceId, "APPROVE_POST", "PUBLISHED", "APPROVED", EventTopics.AUDIT_COMPLETED, reason);
    }

    @Transactional
    public AdminActionResult approve(Long postId, Long auditorId, String traceId, String reason, String idempotencyKey) {
        return idempotent(auditorId, "ADMIN_APPROVE_POST:" + postId, idempotencyKey, postId, "APPROVE_POST",
                requestFingerprint("admin-decision-v1", reason),
                () -> approveInternal(postId, auditorId, traceId, reason));
    }

    private AdminActionResult rejectInternal(Long postId, Long auditorId, String traceId, String reason) {
        return auditPost(postId, null, auditorId, traceId, "REJECT_POST", "REJECTED", "REJECTED", EventTopics.CONTENT_REJECTED, reason);
    }

    @Transactional
    public AdminActionResult reject(Long postId, Long auditorId, String traceId, String reason, String idempotencyKey) {
        return idempotent(auditorId, "ADMIN_REJECT_POST:" + postId, idempotencyKey, postId, "REJECT_POST",
                requestFingerprint("admin-decision-v1", reason),
                () -> rejectInternal(postId, auditorId, traceId, reason));
    }

    private AdminActionResult approveModerationTaskInternal(Long taskId, Long auditorId, String traceId, String reason) {
        Long postId = postIdByTask(taskId);
        return auditPost(postId, taskId, auditorId, traceId, "APPROVE_POST", "PUBLISHED", "APPROVED", EventTopics.AUDIT_COMPLETED, reason);
    }

    @Transactional
    public AdminActionResult approveModerationTask(Long taskId, Long auditorId, String traceId, String reason, String idempotencyKey) {
        return idempotent(auditorId, "ADMIN_APPROVE_TASK:" + taskId, idempotencyKey, taskId, "APPROVE_POST",
                requestFingerprint("admin-decision-v1", reason),
                () -> approveModerationTaskInternal(taskId, auditorId, traceId, reason));
    }

    private AdminActionResult rejectModerationTaskInternal(Long taskId, Long auditorId, String traceId, String reason) {
        Long postId = postIdByTask(taskId);
        return auditPost(postId, taskId, auditorId, traceId, "REJECT_POST", "REJECTED", "REJECTED", EventTopics.CONTENT_REJECTED, reason);
    }

    @Transactional
    public AdminActionResult rejectModerationTask(Long taskId, Long auditorId, String traceId, String reason, String idempotencyKey) {
        return idempotent(auditorId, "ADMIN_REJECT_TASK:" + taskId, idempotencyKey, taskId, "REJECT_POST",
                requestFingerprint("admin-decision-v1", reason),
                () -> rejectModerationTaskInternal(taskId, auditorId, traceId, reason));
    }

    private AdminActionResult escalateModerationTaskInternal(Long taskId, Long auditorId, String traceId, String reason) {
        return updateModerationTask(taskId, auditorId, traceId, "ESCALATE_REVIEW", "ESCALATED", "ESCALATED", reason);
    }

    @Transactional
    public AdminActionResult escalateModerationTask(Long taskId, Long auditorId, String traceId, String reason, String idempotencyKey) {
        return idempotent(auditorId, "ADMIN_ESCALATE_TASK:" + taskId, idempotencyKey, taskId, "ESCALATE_REVIEW",
                requestFingerprint("admin-decision-v1", reason),
                () -> escalateModerationTaskInternal(taskId, auditorId, traceId, reason));
    }

    private AdminActionResult requireChangesInternal(Long taskId, Long auditorId, String traceId, String reason) {
        return updateModerationTask(taskId, auditorId, traceId, "REQUIRE_CHANGES", "CHANGES_REQUIRED", "NORMAL", reason);
    }

    @Transactional
    public AdminActionResult requireChanges(Long taskId, Long auditorId, String traceId, String reason, String idempotencyKey) {
        return idempotent(auditorId, "ADMIN_REQUIRE_CHANGES:" + taskId, idempotencyKey, taskId, "REQUIRE_CHANGES",
                requestFingerprint("admin-decision-v1", reason),
                () -> requireChangesInternal(taskId, auditorId, traceId, reason));
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

    @Transactional
    public AdminActionResult resolveAppeal(Long appealId, Long auditorId, String traceId, String reason,
                                           boolean approved, String idempotencyKey) {
        String operation = approved ? "APPROVE_APPEAL" : "REJECT_APPEAL";
        String resultAction = approved ? "APPEAL_APPROVED" : "APPEAL_REJECTED";
        return idempotent(auditorId, "ADMIN_" + operation + ":" + appealId, idempotencyKey, appealId, resultAction,
                requestFingerprint("admin-appeal-v1", approved, reason),
                () -> resolveAppeal(appealId, auditorId, traceId, reason, approved));
    }

    private AdminActionResult resolveAppeal(Long appealId, Long auditorId, String traceId, String reason,
                                            boolean approved) {
        requireDatabase();
        requireAuditor(auditorId);
        requirePositiveId(appealId, "申诉 ID 无效");
        requireReason(reason);
        String normalizedReason = reason.trim();
        AppealRow appeal = jdbcClient.sql("""
                        SELECT ar.post_id, ar.user_id, ar.reason, p.status AS post_status
                        FROM appeal_record ar
                        JOIN post p ON p.id = ar.post_id
                        WHERE ar.id = :appealId AND ar.status = 'PENDING'
                        FOR UPDATE
                        """)
                .param("appealId", appealId)
                .query((rs, rowNum) -> new AppealRow(rs.getLong("post_id"), rs.getLong("user_id"),
                        rs.getString("reason"), rs.getString("post_status")))
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "待处理申诉不存在或状态已变化"));
        if (!List.of("REJECTED", "CHANGES_REQUIRED").contains(appeal.postStatus())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "投稿当前状态不允许处理申诉");
        }
        String appealStatus = approved ? "APPROVED" : "REJECTED";
        int updated = jdbcClient.sql("""
                        UPDATE appeal_record
                        SET status = :status, handler_id = :auditorId, handled_at = CURRENT_TIMESTAMP
                        WHERE id = :appealId AND status = 'PENDING'
                        """)
                .param("status", appealStatus)
                .param("auditorId", auditorId)
                .param("appealId", appealId)
                .update();
        if (updated == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "待处理申诉不存在或状态已变化");
        }
        if (approved) {
            int postRows = jdbcClient.sql("""
                            UPDATE post
                            SET status = 'PENDING', version = version + 1, submitted_at = CURRENT_TIMESTAMP, review_reason = NULL
                            WHERE id = :postId AND status IN ('REJECTED', 'CHANGES_REQUIRED')
                            """)
                    .param("postId", appeal.postId())
                    .update();
            if (postRows == 0) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "申诉对应投稿状态已变化");
            }
            jdbcClient.sql("""
                            INSERT INTO moderation_task(post_id, author_id, queue_name, machine_result, risk_level,
                                                        risk_score, reason, status, review_level, sla_deadline)
                            VALUES (:postId, :authorId, 'appeal-review', 'APPEAL_ACCEPTED', 'MEDIUM', 0,
                                    :reason, 'PENDING', 'APPEAL', TIMESTAMPADD(HOUR, 48, CURRENT_TIMESTAMP))
                            """)
                    .param("postId", appeal.postId())
                    .param("authorId", appeal.userId())
                    .param("reason", appeal.reason())
                    .update();
        }
        jdbcClient.sql("INSERT INTO post_audit_record(post_id, auditor_id, action, reason) VALUES (:postId, :auditorId, :action, :reason)")
                .param("postId", appeal.postId())
                .param("auditorId", auditorId)
                .param("action", approved ? "APPEAL_APPROVED" : "APPEAL_REJECTED")
                .param("reason", normalizedReason)
                .update();
        logAudit(auditorId, approved ? "APPEAL_APPROVED" : "APPEAL_REJECTED", "APPEAL",
                String.valueOf(appealId), null, traceId,
                Map.of("postId", appeal.postId(), "reason", normalizedReason));
        writeOutbox(appeal.postId(), approved ? "APPEAL_APPROVED" : "APPEAL_REJECTED",
                approved ? "PENDING" : appeal.postStatus(), EventTopics.APPEAL_RESOLVED);
        return new AdminActionResult(approved ? "APPEAL_APPROVED" : "APPEAL_REJECTED",
                Map.of("appealId", appealId, "postId", appeal.postId(), "status", appealStatus,
                        "postStatus", approved ? "PENDING" : appeal.postStatus()));
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

    @Transactional
    public AdminActionResult restrictUser(Long userId, Long auditorId, String traceId, String status,
                                          String reason, String idempotencyKey) {
        requireDatabase();
        requireAuditor(auditorId);
        requirePositiveId(userId, "用户 ID 无效");
        requireReason(reason);
        String normalizedStatus = normalizeRestrictionStatus(status);
        String normalizedReason = reason.trim();
        return idempotent(auditorId, "ADMIN_RESTRICT_USER:" + userId, idempotencyKey, userId, "RESTRICT_USER",
                requestFingerprint("admin-restrict-v1", normalizedStatus, normalizedReason),
                () -> applyRestriction(userId, auditorId, traceId, normalizedStatus, normalizedReason));
    }

    private AdminActionResult applyRestriction(Long userId, Long auditorId, String traceId, String status,
                                               String reason) {
        jdbcClient.sql("""
                            INSERT IGNORE INTO user_risk_profile(user_id, status, risk_score, reason, restricted_until)
                            VALUES (:userId, :status, 90, :reason, TIMESTAMPADD(DAY, 7, CURRENT_TIMESTAMP))
                            """)
                .param("userId", userId)
                .param("status", status)
                .param("reason", reason)
                .update();
        jdbcClient.sql("""
                            UPDATE user_risk_profile
                            SET status = :status, risk_score = 90, reason = :reason,
                                restricted_until = TIMESTAMPADD(DAY, 7, CURRENT_TIMESTAMP)
                            WHERE user_id = :userId
                            """)
                .param("userId", userId)
                .param("status", status)
                .param("reason", reason)
                .update();
        logAudit(auditorId, "RESTRICT_USER", "USER", String.valueOf(userId), null, traceId,
                Map.of("status", status, "reason", reason));
        return new AdminActionResult("RESTRICT_USER", Map.of("userId", userId, "status", status));
    }

    @Transactional
    public AdminActionResult unrestrictUser(Long userId, Long auditorId, String traceId, String reason,
                                            String idempotencyKey) {
        requireDatabase();
        requireAuditor(auditorId);
        requirePositiveId(userId, "用户 ID 无效");
        requireReason(reason);
        String normalizedReason = reason.trim();
        return idempotent(auditorId, "ADMIN_UNRESTRICT_USER:" + userId, idempotencyKey, userId, "UNRESTRICT_USER",
                requestFingerprint("admin-unrestrict-v1", normalizedReason),
                () -> applyUnrestriction(userId, auditorId, traceId, normalizedReason));
    }

    private AdminActionResult applyUnrestriction(Long userId, Long auditorId, String traceId, String reason) {
        jdbcClient.sql("""
                            INSERT INTO user_risk_profile(user_id, status, risk_score, reason, restricted_until)
                            VALUES (:userId, 'NORMAL', 0, '', NULL)
                            ON DUPLICATE KEY UPDATE status = 'NORMAL', risk_score = 0, reason = '', restricted_until = NULL
                            """)
                .param("userId", userId)
                .update();
        logAudit(auditorId, "UNRESTRICT_USER", "USER", String.valueOf(userId), null, traceId,
                Map.of("reason", reason));
        return new AdminActionResult("UNRESTRICT_USER", Map.of("userId", userId, "status", "NORMAL"));
    }

    private String normalizeRestrictionStatus(String status) {
        String normalized = status == null || status.isBlank()
                ? "NO_POST"
                : status.trim().toUpperCase(Locale.ROOT);
        if (!RESTRICTION_STATUSES.contains(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "用户治理状态无效");
        }
        return normalized;
    }

    @Transactional
    public AdminActionResult createTag(String name, Long actorId, String traceId, String idempotencyKey) {
        requireDatabase();
        requireAuditor(actorId);
        String normalizedName = name == null ? "" : name.trim();
        if (normalizedName.isBlank() || normalizedName.length() > 64) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "标签名称必须为 1 至 64 个字符");
        }
        String fingerprint = requestFingerprint("admin-tag-create-v1", normalizedName);
        try {
            Long duplicateId = claimIdempotency(actorId, "CREATE_TAG", idempotencyKey, fingerprint);
            if (duplicateId != null) {
                return new AdminActionResult("CREATE_TAG", Map.of(
                        "tagId", duplicateId, "name", normalizedName, "duplicateRequest", true));
            }
            int inserted = jdbcClient.sql("INSERT IGNORE INTO tag(name, use_count) VALUES (:name, 0)")
                    .param("name", normalizedName).update();
            Long tagId = jdbcClient.sql("SELECT id FROM tag WHERE name = :name")
                    .param("name", normalizedName).query(Long.class).single();
            logAudit(actorId, "CREATE_TAG", "TAG", String.valueOf(tagId), null, traceId,
                    Map.of("name", normalizedName, "created", inserted == 1));
            jdbcClient.sql("UPDATE content_idempotency_record SET resource_id = :tagId WHERE idempotency_key = :key")
                    .param("tagId", tagId).param("key", idempotencyKey).update();
            return new AdminActionResult("CREATE_TAG", Map.of(
                    "tagId", tagId, "name", normalizedName, "created", inserted == 1));
        } catch (DataAccessException ex) {
            throw dependencyUnavailable(ex);
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

    @Transactional
    public AdminActionResult createExperiment(Map<String, Object> payload, Long actorId, String traceId,
                                              String idempotencyKey) {
        requireDatabase();
        requireAuditor(actorId);
        Map<String, Object> safePayload = payload == null ? Map.of() : payload;
        String experimentId = experimentText(safePayload.get("experimentId"),
                "exp-manual-" + requestFingerprint("experiment-id-v1", actorId, idempotencyKey).substring(0, 12),
                "推荐实验 ID", 64);
        if (!experimentId.matches("[A-Za-z0-9][A-Za-z0-9._-]{2,63}")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "推荐实验 ID 格式无效");
        }
        String name = experimentText(safePayload.get("name"), "手动推荐实验", "推荐实验名称", 120);
        String scene = experimentText(safePayload.get("scene"), "home", "推荐场景", 32).toLowerCase(Locale.ROOT);
        if (!scene.matches("[a-z0-9][a-z0-9_-]{1,31}")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "推荐场景格式无效");
        }
        Object weights = safePayload.getOrDefault("weights", Map.of("theme", 1.0));
        String weightsJson = experimentWeightsJson(weights);
        String fingerprint = requestFingerprint("admin-experiment-create-v1", experimentId, name, scene, weightsJson);
        return idempotent(actorId, "CREATE_EXPERIMENT", idempotencyKey, actorId,
                "CREATE_RECOMMEND_EXPERIMENT", fingerprint,
                Map.of("experimentId", experimentId, "scene", scene, "status", "DRAFT"), () -> {
                    int inserted = jdbcClient.sql("""
                                    INSERT IGNORE INTO recommend_experiment(
                                        experiment_id, name, scene, status, traffic_percent, weights)
                                    VALUES (:experimentId, :name, :scene, 'DRAFT', 0, CAST(:weights AS JSON))
                                    """)
                            .param("experimentId", experimentId)
                            .param("name", name)
                            .param("scene", scene)
                            .param("weights", weightsJson)
                            .update();
                    if (inserted != 1) {
                        throw new BusinessException(ErrorCode.DUPLICATE_OPERATION, "推荐实验 ID 已存在");
                    }
                    logAudit(actorId, "CREATE_RECOMMEND_EXPERIMENT", "RECOMMEND_EXPERIMENT", experimentId,
                            null, traceId, Map.of("scene", scene, "status", "DRAFT"));
                    return new AdminActionResult("CREATE_RECOMMEND_EXPERIMENT",
                            Map.of("experimentId", experimentId, "scene", scene, "status", "DRAFT"));
                });
    }

    @Transactional
    public AdminActionResult updateExperimentStatus(String experimentId, String status, Long actorId,
                                                     String traceId, String idempotencyKey) {
        requireDatabase();
        requireAuditor(actorId);
        String normalizedId = experimentText(experimentId, null, "推荐实验 ID", 64);
        String normalizedStatus = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ACTIVE", "PAUSED").contains(normalizedStatus)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "推荐实验状态无效");
        }
        String fingerprint = requestFingerprint("admin-experiment-status-v1", normalizedId, normalizedStatus);
        return idempotent(actorId, "EXPERIMENT_STATUS", idempotencyKey, actorId,
                "UPDATE_RECOMMEND_EXPERIMENT", fingerprint,
                Map.of("experimentId", normalizedId, "status", normalizedStatus), () -> {
                    int changed = jdbcClient.sql("""
                                    UPDATE recommend_experiment
                                    SET status = :status,
                                        traffic_percent = CASE WHEN :status = 'ACTIVE' THEN 100 ELSE 0 END
                                    WHERE experiment_id = :experimentId AND status <> :status
                                    """)
                            .param("status", normalizedStatus)
                            .param("experimentId", normalizedId)
                            .update();
                    if (changed != 1) {
                        Long existing = jdbcClient.sql("SELECT COUNT(*) FROM recommend_experiment WHERE experiment_id = :experimentId")
                                .param("experimentId", normalizedId).query(Long.class).single();
                        if (existing == null || existing == 0) {
                            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "推荐实验不存在");
                        }
                        throw new BusinessException(ErrorCode.VERSION_CONFLICT, "推荐实验状态已变化，请刷新后重试");
                    }
                    logAudit(actorId, "UPDATE_RECOMMEND_EXPERIMENT", "RECOMMEND_EXPERIMENT", normalizedId,
                            null, traceId, Map.of("status", normalizedStatus));
                    return new AdminActionResult("UPDATE_RECOMMEND_EXPERIMENT",
                            Map.of("experimentId", normalizedId, "status", normalizedStatus));
                });
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
            OutboxSnapshot outbox = outboxSnapshot();
            Long moderationPending = oneLong("SELECT COUNT(*) FROM moderation_task WHERE status = 'PENDING'");
            Long riskUsers = oneLong("SELECT COUNT(*) FROM user_risk_profile WHERE status <> 'NORMAL'");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("database", "UP");
            result.put("outboxBacklog", outbox.backlog());
            result.put("outboxRetryable", outbox.retryable());
            result.put("outboxDead", outbox.dead());
            result.put("outboxBlockedByDead", outbox.blockedByDead());
            result.put("outboxScope", "CONTENT_DB");
            result.put("pendingModeration", moderationPending);
            result.put("riskUsers", riskUsers);
            result.put("posts", posts);
            result.put("source", "DB");
            result.put("degraded", outbox.dead() > 0);
            if (outbox.dead() > 0) result.put("degradationReason", "OUTBOX_DEAD");
            return Map.copyOf(result);
        } catch (DataAccessException ex) {
            return Map.of("database", "DOWN", "source", "HEALTH_CHECK", "degraded", true);
        }
    }

    public List<Map<String, Object>> outboxEvents(String status, String topic, int page, int size) {
        requireDatabase();
        int offset = Math.max(page - 1, 0) * size;
        try {
            return jdbcClient.sql("""
                            SELECT id, event_id, topic, aggregate_type, aggregate_id, status, attempts,
                                   next_attempt_at, locked_at, last_error, created_at, published_at
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
                        row.put("attempts", rs.getInt("attempts"));
                        row.put("nextAttemptAt", rs.getTimestamp("next_attempt_at") == null ? null : rs.getTimestamp("next_attempt_at").toInstant());
                        row.put("lockedAt", rs.getTimestamp("locked_at") == null ? null : rs.getTimestamp("locked_at").toInstant());
                        row.put("lastError", rs.getString("last_error"));
                        row.put("createdAt", rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant());
                        row.put("publishedAt", rs.getTimestamp("published_at") == null ? null : rs.getTimestamp("published_at").toInstant());
                        return row;
                    })
                    .list();
        } catch (DataAccessException ex) {
            throw dependencyUnavailable();
        }
    }

    @Transactional
    public AdminActionResult replayOutbox(String topic, int batchSize, Long actorId, String traceId,
                                          String idempotencyKey) {
        requireDatabase();
        requireAuditor(actorId);
        String normalizedTopic = blankToNull(topic);
        int normalizedBatchSize = Math.max(1, Math.min(batchSize, 500));
        String fingerprint = requestFingerprint("admin-outbox-replay-v1", normalizedTopic, normalizedBatchSize);
        String auditTopic = normalizedTopic == null ? "*" : normalizedTopic;
        return idempotent(actorId, "REPLAY_OUTBOX", idempotencyKey, actorId, "REPLAY_OUTBOX", fingerprint,
                Map.of("topic", auditTopic), () -> {
                    int affected = jdbcClient.sql("""
                                    UPDATE outbox_event
                                    SET status = 'NEW', attempts = 0, next_attempt_at = NULL,
                                        locked_at = NULL, locked_by = NULL, last_error = NULL, published_at = NULL
                                    WHERE (status IN ('FAILED', 'DEAD')
                                       OR (status = 'PROCESSING' AND locked_at < TIMESTAMPADD(MINUTE, -5, CURRENT_TIMESTAMP)))
                                      AND (:topic IS NULL OR topic = :topic)
                                    ORDER BY created_at ASC
                                    LIMIT :batchSize
                                    """)
                            .param("topic", normalizedTopic)
                            .param("batchSize", normalizedBatchSize)
                            .update();
                    logAudit(actorId, "REPLAY_OUTBOX", "OUTBOX", auditTopic, null, traceId,
                            Map.of("affected", affected, "batchSize", normalizedBatchSize));
                    return new AdminActionResult("REPLAY_OUTBOX",
                            Map.of("affected", affected, "topic", auditTopic));
                });
    }

    public Map<String, Object> degradation() {
        if (jdbcClient == null) {
            return Map.of("degraded", true, "source", "HEALTH_CHECK", "reason", "DATABASE_UNAVAILABLE");
        }
        try {
            OutboxSnapshot outbox = outboxSnapshot();
            Long impressions = oneLong("SELECT COUNT(*) FROM recommendation_impression");
            Long events = oneLong("SELECT COUNT(*) FROM recommend_event_log");
            Long moderationPending = oneLong("SELECT COUNT(*) FROM moderation_task WHERE status = 'PENDING'");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("degraded", outbox.dead() > 0);
            result.put("source", "DB");
            result.put("outboxBacklog", outbox.backlog());
            result.put("outboxRetryable", outbox.retryable());
            result.put("outboxDead", outbox.dead());
            result.put("outboxBlockedByDead", outbox.blockedByDead());
            result.put("outboxScope", "CONTENT_DB");
            result.put("recommendImpressions", impressions);
            result.put("recommendEvents", events);
            result.put("pendingModeration", moderationPending);
            if (outbox.dead() > 0) result.put("reason", "OUTBOX_DEAD");
            return Map.copyOf(result);
        } catch (DataAccessException ex) {
            return Map.of("degraded", true, "source", "HEALTH_CHECK", "reason", "DATABASE_UNAVAILABLE");
        }
    }

    private OutboxSnapshot outboxSnapshot() {
        long retryable = oneLong("SELECT COUNT(*) FROM outbox_event WHERE status IN ('NEW', 'FAILED', 'PROCESSING')");
        long dead = oneLong("SELECT COUNT(*) FROM outbox_event WHERE status = 'DEAD'");
        long blockedByDead = oneLong("""
                SELECT COUNT(*)
                FROM outbox_event current_event
                WHERE current_event.status <> 'PUBLISHED'
                  AND EXISTS (
                      SELECT 1 FROM outbox_event prior_event
                      WHERE prior_event.topic = current_event.topic
                        AND prior_event.aggregate_id = current_event.aggregate_id
                        AND prior_event.id < current_event.id
                        AND prior_event.status = 'DEAD'
                  )
                """);
        return new OutboxSnapshot(retryable + dead, retryable, dead, blockedByDead);
    }

    private AdminActionResult auditPost(Long postId, Long requestedTaskId, Long auditorId, String traceId,
                                        String action, String postStatus, String taskStatus, String topic, String reason) {
        requireDatabase();
        requireAuditor(auditorId);
        requirePositiveId(postId, "内容 ID 无效");
        requireReason(reason);
        try {
            Long taskId = activeTaskId(postId, requestedTaskId);
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
                            WHERE id = :taskId AND post_id = :postId AND status IN ('PENDING', 'ESCALATED')
                            """)
                    .param("taskStatus", taskStatus)
                    .param("auditorId", auditorId)
                    .param("taskId", taskId)
                    .param("postId", postId)
                    .update();
            if (taskRows == 0) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "审核任务不存在或已处理");
            }
            jdbcClient.sql("""
                            UPDATE moderation_task
                            SET status = 'CANCELLED', reviewer_id = :auditorId, reviewed_at = CURRENT_TIMESTAMP
                            WHERE post_id = :postId AND id <> :taskId AND status IN ('PENDING', 'ESCALATED')
                            """)
                    .param("auditorId", auditorId)
                    .param("postId", postId)
                    .param("taskId", taskId)
                    .update();
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
                                UPDATE media_asset
                                SET status = 'APPROVED'
                                WHERE status = 'PENDING_REVIEW'
                                  AND id IN (SELECT media_id FROM post_media WHERE post_id = :postId)
                                """)
                        .param("postId", postId)
                        .update();
            }
            logAudit(auditorId, action, "POST", String.valueOf(postId), null, traceId, Map.of("status", postStatus));
            writeOutbox(postId, action, postStatus, topic);
            return new AdminActionResult(action, Map.of("postId", postId, "status", postStatus, "event", topic));
        } catch (DataAccessException ex) {
            throw dependencyUnavailable(ex);
        }
    }

    private Long activeTaskId(Long postId, Long requestedTaskId) {
        if (requestedTaskId != null) {
            requirePositiveId(requestedTaskId, "审核任务 ID 无效");
            return jdbcClient.sql("""
                            SELECT id FROM moderation_task
                            WHERE id = :taskId AND post_id = :postId AND status IN ('PENDING', 'ESCALATED')
                            FOR UPDATE
                            """)
                    .param("taskId", requestedTaskId)
                    .param("postId", postId)
                    .query(Long.class)
                    .optional()
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "审核任务不存在或已处理"));
        }
        return jdbcClient.sql("""
                        SELECT id FROM moderation_task
                        WHERE post_id = :postId AND status IN ('PENDING', 'ESCALATED')
                        ORDER BY id DESC LIMIT 1 FOR UPDATE
                        """)
                .param("postId", postId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "审核任务不存在或已处理"));
    }

    private AdminActionResult updateModerationTask(Long taskId, Long auditorId, String traceId, String action, String status, String reviewLevel, String reason) {
        requireDatabase();
        requireAuditor(auditorId);
        requirePositiveId(taskId, "审核任务 ID 无效");
        requireReason(reason);
        String normalizedReason = reason.trim();
        try {
            Long postId = jdbcClient.sql("""
                            SELECT mt.post_id
                            FROM moderation_task mt
                            JOIN post p ON p.id = mt.post_id
                            WHERE mt.id = :taskId AND p.status = 'PENDING'
                            FOR UPDATE
                            """)
                    .param("taskId", taskId)
                    .query(Long.class)
                    .optional()
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.RESOURCE_NOT_FOUND, "审核任务不存在或投稿状态已变化"));
            int updated = jdbcClient.sql("""
                            UPDATE moderation_task
                            SET status = :status, review_level = :reviewLevel, reviewer_id = :auditorId, reviewed_at = CURRENT_TIMESTAMP
                            WHERE id = :taskId
                              AND ((:status = 'ESCALATED' AND status = 'PENDING')
                                OR (:status <> 'ESCALATED' AND status IN ('PENDING', 'ESCALATED')))
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
                    .param("reason", normalizedReason)
                    .update();
            if ("CHANGES_REQUIRED".equals(status)) {
                int postRows = jdbcClient.sql("UPDATE post SET status = 'CHANGES_REQUIRED', review_reason = :reason WHERE id = :postId AND status = 'PENDING'")
                        .param("reason", normalizedReason)
                        .param("postId", postId)
                        .update();
                if (postRows == 0) {
                    throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "待审核内容不存在或状态已变化");
                }
                jdbcClient.sql("""
                                UPDATE moderation_task
                                SET status = 'CANCELLED', reviewer_id = :auditorId, reviewed_at = CURRENT_TIMESTAMP
                                WHERE post_id = :postId AND id <> :taskId AND status IN ('PENDING', 'ESCALATED')
                                """)
                        .param("auditorId", auditorId)
                        .param("postId", postId)
                        .param("taskId", taskId)
                        .update();
                jdbcClient.sql("INSERT INTO post_audit_record(post_id, auditor_id, action, reason) VALUES (:postId, :auditorId, :action, :reason)")
                        .param("postId", postId)
                        .param("auditorId", auditorId)
                        .param("action", action)
                        .param("reason", normalizedReason)
                        .update();
                writeOutbox(postId, action, "CHANGES_REQUIRED", EventTopics.CONTENT_REJECTED);
            }
            logAudit(auditorId, action, "MODERATION_TASK", String.valueOf(taskId), null, traceId,
                    Map.of("postId", postId, "status", status, "reason", normalizedReason));
            return new AdminActionResult(action, Map.of("taskId", taskId, "postId", postId, "status", status));
        } catch (DataAccessException ex) {
            throw dependencyUnavailable(ex);
        }
    }

    private Long postIdByTask(Long taskId) {
        requireDatabase();
        requirePositiveId(taskId, "审核任务 ID 无效");
        try {
            return jdbcClient.sql("SELECT post_id FROM moderation_task WHERE id = :taskId")
                    .param("taskId", taskId)
                    .query(Long.class)
                    .optional()
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "审核任务不存在"));
        } catch (DataAccessException ex) {
            throw dependencyUnavailable(ex);
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
            EventEnvelope event = EventEnvelope.now(topic, "dc-admin", String.valueOf(postId),
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

    private AdminActionResult idempotent(Long actorId, String operation, String key, Long resourceId,
                                         String action, String requestFingerprint,
                                         Supplier<AdminActionResult> mutation) {
        return idempotent(actorId, operation, key, resourceId, action, requestFingerprint,
                Map.of("resourceId", resourceId), mutation);
    }

    private AdminActionResult idempotent(Long actorId, String operation, String key, Long resourceId,
                                         String action, String requestFingerprint,
                                         Map<String, Object> replayAttributes,
                                         Supplier<AdminActionResult> mutation) {
        try {
            Long duplicateId = claimIdempotency(actorId, operation, key, requestFingerprint);
            if (duplicateId != null) {
                Map<String, Object> attributes = new LinkedHashMap<>(replayAttributes);
                attributes.putIfAbsent("resourceId", duplicateId);
                attributes.put("duplicateRequest", true);
                return new AdminActionResult(action, Map.copyOf(attributes));
            }
            AdminActionResult result = mutation.get();
            jdbcClient.sql("UPDATE content_idempotency_record SET resource_id = :resourceId WHERE idempotency_key = :key")
                    .param("resourceId", resourceId)
                    .param("key", key)
                    .update();
            return result;
        } catch (DataAccessException ex) {
            throw dependencyUnavailable(ex);
        }
    }

    private Long claimIdempotency(Long actorId, String operation, String key, String requestFingerprint) {
        requireDatabase();
        requireAuditor(actorId);
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "审核写操作必须提供有效的 Idempotency-Key");
        }
        int inserted = jdbcClient.sql("""
                        INSERT IGNORE INTO content_idempotency_record(
                            idempotency_key, user_id, operation, request_fingerprint)
                        VALUES (:key, :actorId, :operation, :requestFingerprint)
                        """)
                .param("key", key)
                .param("actorId", actorId)
                .param("operation", operation)
                .param("requestFingerprint", requestFingerprint)
                .update();
        if (inserted == 1) return null;
        IdempotencyRecord existing = jdbcClient.sql("""
                        SELECT user_id, operation, request_fingerprint, resource_id
                        FROM content_idempotency_record WHERE idempotency_key = :key
                        """)
                .param("key", key)
                .query((rs, rowNum) -> new IdempotencyRecord(
                        rs.getLong("user_id"), rs.getString("operation"), rs.getString("request_fingerprint"),
                        (Long) rs.getObject("resource_id")))
                .single();
        boolean legacyRecord = LEGACY_REQUEST_FINGERPRINT.equals(existing.requestFingerprint());
        if (!existing.actorId().equals(actorId) || !existing.operation().equals(operation)
                || (!legacyRecord && !existing.requestFingerprint().equals(requestFingerprint))) {
            throw new BusinessException(ErrorCode.DUPLICATE_OPERATION,
                    "Idempotency-Key 已被其他审核操作或请求载荷使用");
        }
        if (existing.resourceId() == null) {
            throw new BusinessException(ErrorCode.DUPLICATE_OPERATION, "相同审核请求正在处理中");
        }
        return existing.resourceId();
    }

    private String requestFingerprint(Object... values) {
        StringBuilder canonical = new StringBuilder();
        for (Object value : values) {
            if (value == null) {
                canonical.append("-1:|");
            } else {
                String text = String.valueOf(value);
                canonical.append(text.length()).append(':').append(text).append('|');
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String experimentText(Object value, String fallback, String fieldName, int maxLength) {
        String text = value == null ? fallback : String.valueOf(value).trim();
        if (text == null || text.isBlank() || "null".equalsIgnoreCase(text)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, fieldName + "不能为空");
        }
        if (text.length() > maxLength) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, fieldName + "过长");
        }
        return text;
    }

    private String experimentWeightsJson(Object weights) {
        if (!(weights instanceof Map<?, ?> map) || map.isEmpty() || map.size() > 12) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "推荐权重必须是包含 1 至 12 项的对象");
        }
        Map<String, Double> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey()).trim();
            if (!key.matches("[A-Za-z][A-Za-z0-9_-]{0,31}")) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "推荐权重名称格式无效");
            }
            if (!(entry.getValue() instanceof Number number)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "推荐权重必须是数字");
            }
            double value = number.doubleValue();
            if (!Double.isFinite(value) || value < 0 || value > 100) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "推荐权重必须在 0 至 100 之间");
            }
            normalized.put(key, value);
        }
        if (normalized.values().stream().allMatch(value -> value == 0)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "推荐权重不能全部为零");
        }
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "推荐权重无法序列化");
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

    private BusinessException dependencyUnavailable(DataAccessException cause) {
        BusinessException failure = dependencyUnavailable();
        failure.initCause(cause);
        return failure;
    }

    private void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "审核操作必须填写理由");
        }
        if (reason.length() > 512) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "审核理由不能超过 512 个字符");
        }
    }

    private void requireAuditor(Long auditorId) {
        if (auditorId == null || auditorId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
    }

    private void requirePositiveId(Long value, String message) {
        if (value == null || value <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, message);
        }
    }

    private Long oneLong(String sql) {
        Long value = jdbcClient.sql(sql).query(Long.class).single();
        return value == null ? 0 : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record IdempotencyRecord(Long actorId, String operation, String requestFingerprint, Long resourceId) {
    }

    private record AppealRow(Long postId, Long userId, String reason, String postStatus) {
    }

    private record OutboxSnapshot(long backlog, long retryable, long dead, long blockedByDead) {
    }

}
