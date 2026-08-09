package io.github.devcircle.admin.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.admin.api.AdminActionResult;
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
    private final AdminModerationService moderationService;

    public AdminService() {
        this.jdbcClient = null;
        this.objectMapper = new ObjectMapper();
        this.moderationService = new AdminModerationService();
    }

    @Autowired
    public AdminService(JdbcClient jdbcClient, ObjectMapper objectMapper, AdminModerationService moderationService) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.moderationService = moderationService;
    }

    public AdminService(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this(jdbcClient, objectMapper,
                new AdminModerationService(jdbcClient, new AdminMutationSupport(jdbcClient, objectMapper)));
    }

    @Transactional
    public AdminActionResult approve(Long postId, Long auditorId, String traceId, String reason, String idempotencyKey) {
        return moderationService.approve(postId, auditorId, traceId, reason, idempotencyKey);
    }

    @Transactional
    public AdminActionResult reject(Long postId, Long auditorId, String traceId, String reason, String idempotencyKey) {
        return moderationService.reject(postId, auditorId, traceId, reason, idempotencyKey);
    }

    @Transactional
    public AdminActionResult approveModerationTask(Long taskId, Long auditorId, String traceId, String reason, String idempotencyKey) {
        return moderationService.approveModerationTask(taskId, auditorId, traceId, reason, idempotencyKey);
    }

    @Transactional
    public AdminActionResult rejectModerationTask(Long taskId, Long auditorId, String traceId, String reason, String idempotencyKey) {
        return moderationService.rejectModerationTask(taskId, auditorId, traceId, reason, idempotencyKey);
    }

    @Transactional
    public AdminActionResult escalateModerationTask(Long taskId, Long auditorId, String traceId, String reason, String idempotencyKey) {
        return moderationService.escalateModerationTask(taskId, auditorId, traceId, reason, idempotencyKey);
    }

    @Transactional
    public AdminActionResult requireChanges(Long taskId, Long auditorId, String traceId, String reason, String idempotencyKey) {
        return moderationService.requireChanges(taskId, auditorId, traceId, reason, idempotencyKey);
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

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record IdempotencyRecord(Long actorId, String operation, String requestFingerprint, Long resourceId) {
    }

    private record AppealRow(Long postId, Long userId, String reason, String postStatus) {
    }

}
