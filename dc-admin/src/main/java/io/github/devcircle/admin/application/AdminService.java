package io.github.devcircle.admin.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.admin.api.AdminActionResult;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AdminService {
    private static final Set<String> RESTRICTION_STATUSES = Set.of("WATCH", "RATE_LIMITED", "NO_POST", "BANNED");
    private final JdbcClient jdbcClient;
    private final AdminModerationService moderationService;
    private final AdminMutationSupport mutationSupport;
    private final AdminExperimentService experimentService;

    public AdminService() {
        this.jdbcClient = null;
        ObjectMapper objectMapper = new ObjectMapper();
        this.mutationSupport = new AdminMutationSupport(null, objectMapper);
        this.moderationService = new AdminModerationService();
        this.experimentService = new AdminExperimentService(null, objectMapper, mutationSupport);
    }

    @Autowired
    public AdminService(JdbcClient jdbcClient,
                        AdminModerationService moderationService,
                        AdminMutationSupport mutationSupport,
                        AdminExperimentService experimentService) {
        this.jdbcClient = jdbcClient;
        this.moderationService = moderationService;
        this.mutationSupport = mutationSupport;
        this.experimentService = experimentService;
    }

    public AdminService(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        AdminMutationSupport support = new AdminMutationSupport(jdbcClient, objectMapper);
        this.jdbcClient = jdbcClient;
        this.moderationService = new AdminModerationService(jdbcClient, support);
        this.mutationSupport = support;
        this.experimentService = new AdminExperimentService(jdbcClient, objectMapper, support);
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
        return mutationSupport.idempotent(auditorId, "ADMIN_" + operation + ":" + appealId,
                idempotencyKey, appealId, resultAction,
                mutationSupport.requestFingerprint("admin-appeal-v1", approved, reason),
                () -> resolveAppeal(appealId, auditorId, traceId, reason, approved));
    }

    private AdminActionResult resolveAppeal(Long appealId, Long auditorId, String traceId, String reason,
                                            boolean approved) {
        mutationSupport.requireDatabase();
        mutationSupport.requireAuditor(auditorId);
        mutationSupport.requirePositiveId(appealId, "申诉 ID 无效");
        mutationSupport.requireReason(reason);
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
        mutationSupport.logAudit(auditorId, approved ? "APPEAL_APPROVED" : "APPEAL_REJECTED", "APPEAL",
                String.valueOf(appealId), null, traceId,
                Map.of("postId", appeal.postId(), "reason", normalizedReason));
        mutationSupport.writeOutbox(appeal.postId(), approved ? "APPEAL_APPROVED" : "APPEAL_REJECTED",
                approved ? "PENDING" : appeal.postStatus(), EventTopics.APPEAL_RESOLVED);
        return new AdminActionResult(approved ? "APPEAL_APPROVED" : "APPEAL_REJECTED",
                Map.of("appealId", appealId, "postId", appeal.postId(), "status", appealStatus,
                        "postStatus", approved ? "PENDING" : appeal.postStatus()));
    }

    @Transactional
    public AdminActionResult restrictUser(Long userId, Long auditorId, String traceId, String status,
                                          String reason, String idempotencyKey) {
        mutationSupport.requireDatabase();
        mutationSupport.requireAuditor(auditorId);
        mutationSupport.requirePositiveId(userId, "用户 ID 无效");
        mutationSupport.requireReason(reason);
        String normalizedStatus = normalizeRestrictionStatus(status);
        String normalizedReason = reason.trim();
        return mutationSupport.idempotent(auditorId, "ADMIN_RESTRICT_USER:" + userId, idempotencyKey,
                userId, "RESTRICT_USER",
                mutationSupport.requestFingerprint("admin-restrict-v1", normalizedStatus, normalizedReason),
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
        mutationSupport.logAudit(auditorId, "RESTRICT_USER", "USER", String.valueOf(userId), null, traceId,
                Map.of("status", status, "reason", reason));
        return new AdminActionResult("RESTRICT_USER", Map.of("userId", userId, "status", status));
    }

    @Transactional
    public AdminActionResult unrestrictUser(Long userId, Long auditorId, String traceId, String reason,
                                            String idempotencyKey) {
        mutationSupport.requireDatabase();
        mutationSupport.requireAuditor(auditorId);
        mutationSupport.requirePositiveId(userId, "用户 ID 无效");
        mutationSupport.requireReason(reason);
        String normalizedReason = reason.trim();
        return mutationSupport.idempotent(auditorId, "ADMIN_UNRESTRICT_USER:" + userId, idempotencyKey,
                userId, "UNRESTRICT_USER",
                mutationSupport.requestFingerprint("admin-unrestrict-v1", normalizedReason),
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
        mutationSupport.logAudit(auditorId, "UNRESTRICT_USER", "USER", String.valueOf(userId), null, traceId,
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
        mutationSupport.requireDatabase();
        mutationSupport.requireAuditor(actorId);
        String normalizedName = name == null ? "" : name.trim();
        if (normalizedName.isBlank() || normalizedName.length() > 64) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "标签名称必须为 1 至 64 个字符");
        }
        String fingerprint = mutationSupport.requestFingerprint("admin-tag-create-v1", normalizedName);
        try {
            Long duplicateId = mutationSupport.claimIdempotency(
                    actorId, "CREATE_TAG", idempotencyKey, fingerprint);
            if (duplicateId != null) {
                return new AdminActionResult("CREATE_TAG", Map.of(
                        "tagId", duplicateId, "name", normalizedName, "duplicateRequest", true));
            }
            int inserted = jdbcClient.sql("INSERT IGNORE INTO tag(name, use_count) VALUES (:name, 0)")
                    .param("name", normalizedName).update();
            Long tagId = jdbcClient.sql("SELECT id FROM tag WHERE name = :name")
                    .param("name", normalizedName).query(Long.class).single();
            mutationSupport.logAudit(actorId, "CREATE_TAG", "TAG", String.valueOf(tagId), null, traceId,
                    Map.of("name", normalizedName, "created", inserted == 1));
            jdbcClient.sql("UPDATE content_idempotency_record SET resource_id = :tagId WHERE idempotency_key = :key")
                    .param("tagId", tagId).param("key", idempotencyKey).update();
            return new AdminActionResult("CREATE_TAG", Map.of(
                    "tagId", tagId, "name", normalizedName, "created", inserted == 1));
        } catch (DataAccessException ex) {
            throw mutationSupport.dependencyUnavailable(ex);
        }
    }

    @Transactional
    public AdminActionResult createExperiment(Map<String, Object> payload, Long actorId, String traceId,
                                              String idempotencyKey) {
        return experimentService.create(payload, actorId, traceId, idempotencyKey);
    }

    @Transactional
    public AdminActionResult updateExperimentStatus(String experimentId, String status, Long actorId,
                                                     String traceId, String idempotencyKey) {
        return experimentService.updateStatus(experimentId, status, actorId, traceId, idempotencyKey);
    }

    @Transactional
    public AdminActionResult replayOutbox(String topic, int batchSize, Long actorId, String traceId,
                                          String idempotencyKey) {
        mutationSupport.requireDatabase();
        mutationSupport.requireAuditor(actorId);
        String normalizedTopic = topic == null || topic.isBlank() ? null : topic;
        int normalizedBatchSize = Math.max(1, Math.min(batchSize, 500));
        String fingerprint = mutationSupport.requestFingerprint(
                "admin-outbox-replay-v1", normalizedTopic, normalizedBatchSize);
        String auditTopic = normalizedTopic == null ? "*" : normalizedTopic;
        return mutationSupport.idempotent(
                actorId, "REPLAY_OUTBOX", idempotencyKey, actorId, "REPLAY_OUTBOX", fingerprint,
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
                    mutationSupport.logAudit(actorId, "REPLAY_OUTBOX", "OUTBOX", auditTopic, null, traceId,
                            Map.of("affected", affected, "batchSize", normalizedBatchSize));
                    return new AdminActionResult("REPLAY_OUTBOX",
                            Map.of("affected", affected, "topic", auditTopic));
                });
    }

    private record AppealRow(Long postId, Long userId, String reason, String postStatus) {
    }

}
