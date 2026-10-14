package io.github.devcircle.admin.application;

import io.github.devcircle.admin.api.AdminActionResult;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** Transactional moderation use cases, separated from the wider admin console. */
@Service
public class AdminModerationService {
    private final JdbcClient jdbcClient;
    private final AdminMutationSupport support;

    public AdminModerationService() {
        this.jdbcClient = null;
        this.support = new AdminMutationSupport(null, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Autowired
    public AdminModerationService(JdbcClient jdbcClient, AdminMutationSupport support) {
        this.jdbcClient = jdbcClient;
        this.support = support;
    }

    private AdminActionResult approveInternal(Long postId, Long auditorId, String traceId, String reason) {
        return auditPost(postId, null, auditorId, traceId, "APPROVE_POST", "PUBLISHED", "APPROVED",
                EventTopics.AUDIT_COMPLETED, reason);
    }

    @Transactional
    public AdminActionResult approve(Long postId, Long auditorId, String traceId, String reason, String idempotencyKey) {
        return support.idempotent(auditorId, "ADMIN_APPROVE_POST:" + postId, idempotencyKey, postId, "APPROVE_POST",
                support.requestFingerprint("admin-decision-v1", reason),
                () -> approveInternal(postId, auditorId, traceId, reason));
    }

    private AdminActionResult rejectInternal(Long postId, Long auditorId, String traceId, String reason) {
        return auditPost(postId, null, auditorId, traceId, "REJECT_POST", "REJECTED", "REJECTED",
                EventTopics.CONTENT_REJECTED, reason);
    }

    @Transactional
    public AdminActionResult reject(Long postId, Long auditorId, String traceId, String reason, String idempotencyKey) {
        return support.idempotent(auditorId, "ADMIN_REJECT_POST:" + postId, idempotencyKey, postId, "REJECT_POST",
                support.requestFingerprint("admin-decision-v1", reason),
                () -> rejectInternal(postId, auditorId, traceId, reason));
    }

    private AdminActionResult approveModerationTaskInternal(Long taskId, Long auditorId, String traceId, String reason) {
        Long postId = postIdByTask(taskId);
        return auditPost(postId, taskId, auditorId, traceId, "APPROVE_POST", "PUBLISHED", "APPROVED",
                EventTopics.AUDIT_COMPLETED, reason);
    }

    @Transactional
    public AdminActionResult approveModerationTask(Long taskId, Long auditorId, String traceId, String reason,
                                                   String idempotencyKey) {
        return support.idempotent(auditorId, "ADMIN_APPROVE_TASK:" + taskId, idempotencyKey, taskId, "APPROVE_POST",
                support.requestFingerprint("admin-decision-v1", reason),
                () -> approveModerationTaskInternal(taskId, auditorId, traceId, reason));
    }

    private AdminActionResult rejectModerationTaskInternal(Long taskId, Long auditorId, String traceId, String reason) {
        Long postId = postIdByTask(taskId);
        return auditPost(postId, taskId, auditorId, traceId, "REJECT_POST", "REJECTED", "REJECTED",
                EventTopics.CONTENT_REJECTED, reason);
    }

    @Transactional
    public AdminActionResult rejectModerationTask(Long taskId, Long auditorId, String traceId, String reason,
                                                  String idempotencyKey) {
        return support.idempotent(auditorId, "ADMIN_REJECT_TASK:" + taskId, idempotencyKey, taskId, "REJECT_POST",
                support.requestFingerprint("admin-decision-v1", reason),
                () -> rejectModerationTaskInternal(taskId, auditorId, traceId, reason));
    }

    private AdminActionResult escalateModerationTaskInternal(Long taskId, Long auditorId, String traceId, String reason) {
        return updateModerationTask(taskId, auditorId, traceId, "ESCALATE_REVIEW", "ESCALATED", "ESCALATED", reason);
    }

    @Transactional
    public AdminActionResult escalateModerationTask(Long taskId, Long auditorId, String traceId, String reason,
                                                    String idempotencyKey) {
        return support.idempotent(auditorId, "ADMIN_ESCALATE_TASK:" + taskId, idempotencyKey, taskId, "ESCALATE_REVIEW",
                support.requestFingerprint("admin-decision-v1", reason),
                () -> escalateModerationTaskInternal(taskId, auditorId, traceId, reason));
    }

    private AdminActionResult requireChangesInternal(Long taskId, Long auditorId, String traceId, String reason) {
        return updateModerationTask(taskId, auditorId, traceId, "REQUIRE_CHANGES", "CHANGES_REQUIRED", "NORMAL", reason);
    }

    @Transactional
    public AdminActionResult requireChanges(Long taskId, Long auditorId, String traceId, String reason,
                                            String idempotencyKey) {
        return support.idempotent(auditorId, "ADMIN_REQUIRE_CHANGES:" + taskId, idempotencyKey, taskId,
                "REQUIRE_CHANGES", support.requestFingerprint("admin-decision-v1", reason),
                () -> requireChangesInternal(taskId, auditorId, traceId, reason));
    }

    private AdminActionResult auditPost(Long postId, Long requestedTaskId, Long auditorId, String traceId,
                                        String action, String postStatus, String taskStatus, String topic, String reason) {
        support.requireDatabase();
        support.requireAuditor(auditorId);
        support.requirePositiveId(postId, "内容 ID 无效");
        support.requireReason(reason);
        try {
            Long taskId = activeTaskId(postId, requestedTaskId);
            int postRows = jdbcClient.sql("UPDATE post SET version = version + 1, status = :status, review_reason = :reason WHERE id = :postId AND status = 'PENDING'")
                    .param("status", postStatus).param("reason", reason.trim()).param("postId", postId).update();
            if (postRows == 0) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "待审核内容不存在或状态已变化");
            }
            int taskRows = jdbcClient.sql("""
                            UPDATE moderation_task
                            SET status = :taskStatus, reviewer_id = :auditorId, reviewed_at = CURRENT_TIMESTAMP
                            WHERE id = :taskId AND post_id = :postId AND status IN ('PENDING', 'ESCALATED')
                            """)
                    .param("taskStatus", taskStatus).param("auditorId", auditorId)
                    .param("taskId", taskId).param("postId", postId).update();
            if (taskRows == 0) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "审核任务不存在或已处理");
            }
            jdbcClient.sql("""
                            UPDATE moderation_task
                            SET status = 'CANCELLED', reviewer_id = :auditorId, reviewed_at = CURRENT_TIMESTAMP
                            WHERE post_id = :postId AND id <> :taskId AND status IN ('PENDING', 'ESCALATED')
                            """)
                    .param("auditorId", auditorId).param("postId", postId).param("taskId", taskId).update();
            insertPostAudit(postId, auditorId, action, reason.trim());
            jdbcClient.sql("""
                            INSERT INTO moderation_decision(task_id, post_id, reviewer_id, action, reason)
                            VALUES (:taskId, :postId, :auditorId, :action, :reason)
                            """)
                    .param("taskId", taskId).param("postId", postId).param("auditorId", auditorId)
                    .param("action", action).param("reason", reason.trim()).update();
            if ("PUBLISHED".equals(postStatus)) {
                jdbcClient.sql("""
                                UPDATE media_asset SET status = 'APPROVED'
                                WHERE status = 'PENDING_REVIEW'
                                  AND id IN (SELECT media_id FROM post_media WHERE post_id = :postId)
                                """).param("postId", postId).update();
            }
            support.logAudit(auditorId, action, "POST", String.valueOf(postId), null, traceId,
                    Map.of("status", postStatus));
            support.writeOutbox(postId, action, postStatus, topic);
            return new AdminActionResult(action, Map.of("postId", postId, "status", postStatus, "event", topic));
        } catch (org.springframework.dao.DataAccessException ex) {
            throw support.dependencyUnavailable(ex);
        }
    }

    private void insertPostAudit(Long postId, Long auditorId, String action, String reason) {
        jdbcClient.sql("INSERT INTO post_audit_record(post_id, auditor_id, action, reason) VALUES (:postId, :auditorId, :action, :reason)")
                .param("postId", postId).param("auditorId", auditorId).param("action", action).param("reason", reason).update();
    }

    private Long activeTaskId(Long postId, Long requestedTaskId) {
        if (requestedTaskId != null) {
            support.requirePositiveId(requestedTaskId, "审核任务 ID 无效");
            return jdbcClient.sql("""
                            SELECT id FROM moderation_task
                            WHERE id = :taskId AND post_id = :postId AND status IN ('PENDING', 'ESCALATED')
                            FOR UPDATE
                            """).param("taskId", requestedTaskId).param("postId", postId).query(Long.class).optional()
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "审核任务不存在或已处理"));
        }
        return jdbcClient.sql("""
                        SELECT id FROM moderation_task
                        WHERE post_id = :postId AND status IN ('PENDING', 'ESCALATED')
                        ORDER BY id DESC LIMIT 1 FOR UPDATE
                        """).param("postId", postId).query(Long.class).optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "审核任务不存在或已处理"));
    }

    private AdminActionResult updateModerationTask(Long taskId, Long auditorId, String traceId, String action,
                                                   String status, String reviewLevel, String reason) {
        support.requireDatabase();
        support.requireAuditor(auditorId);
        support.requirePositiveId(taskId, "审核任务 ID 无效");
        support.requireReason(reason);
        String normalizedReason = reason.trim();
        try {
            Long postId = jdbcClient.sql("""
                            SELECT mt.post_id FROM moderation_task mt JOIN post p ON p.id = mt.post_id
                            WHERE mt.id = :taskId AND p.status = 'PENDING' FOR UPDATE
                            """).param("taskId", taskId).query(Long.class).optional()
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "审核任务不存在或投稿状态已变化"));
            int updated = jdbcClient.sql("""
                            UPDATE moderation_task
                            SET status = :status, review_level = :reviewLevel, reviewer_id = :auditorId,
                                reviewed_at = CURRENT_TIMESTAMP
                            WHERE id = :taskId
                              AND ((:status = 'ESCALATED' AND status = 'PENDING')
                                OR (:status <> 'ESCALATED' AND status IN ('PENDING', 'ESCALATED')))
                            """).param("status", status).param("reviewLevel", reviewLevel)
                    .param("auditorId", auditorId).param("taskId", taskId).update();
            if (updated == 0) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "审核任务不存在或已处理");
            }
            jdbcClient.sql("""
                            INSERT INTO moderation_decision(task_id, post_id, reviewer_id, action, reason)
                            VALUES (:taskId, :postId, :auditorId, :action, :reason)
                            """).param("taskId", taskId).param("postId", postId).param("auditorId", auditorId)
                    .param("action", action).param("reason", normalizedReason).update();
            if ("CHANGES_REQUIRED".equals(status)) {
                int postRows = jdbcClient.sql("UPDATE post SET version = version + 1, status = 'CHANGES_REQUIRED', review_reason = :reason WHERE id = :postId AND status = 'PENDING'")
                        .param("reason", normalizedReason).param("postId", postId).update();
                if (postRows == 0) {
                    throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "待审核内容不存在或状态已变化");
                }
                jdbcClient.sql("""
                                UPDATE moderation_task SET status = 'CANCELLED', reviewer_id = :auditorId,
                                       reviewed_at = CURRENT_TIMESTAMP
                                WHERE post_id = :postId AND id <> :taskId AND status IN ('PENDING', 'ESCALATED')
                                """).param("auditorId", auditorId).param("postId", postId).param("taskId", taskId).update();
                insertPostAudit(postId, auditorId, action, normalizedReason);
                support.writeOutbox(postId, action, "CHANGES_REQUIRED", EventTopics.CONTENT_REJECTED);
            }
            support.logAudit(auditorId, action, "MODERATION_TASK", String.valueOf(taskId), null, traceId,
                    Map.of("postId", postId, "status", status, "reason", normalizedReason));
            return new AdminActionResult(action, Map.of("taskId", taskId, "postId", postId, "status", status));
        } catch (org.springframework.dao.DataAccessException ex) {
            throw support.dependencyUnavailable(ex);
        }
    }

    private Long postIdByTask(Long taskId) {
        support.requireDatabase();
        support.requirePositiveId(taskId, "审核任务 ID 无效");
        try {
            return jdbcClient.sql("SELECT post_id FROM moderation_task WHERE id = :taskId").param("taskId", taskId)
                    .query(Long.class).optional()
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "审核任务不存在"));
        } catch (org.springframework.dao.DataAccessException ex) {
            throw support.dependencyUnavailable(ex);
        }
    }
}
