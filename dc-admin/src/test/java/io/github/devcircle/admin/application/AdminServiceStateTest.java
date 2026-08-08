package io.github.devcircle.admin.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.admin.application.query.OutboxFleetSnapshot;
import io.github.devcircle.admin.application.query.OutboxSourceView;
import io.github.devcircle.admin.infrastructure.JdbcAdminOpsReadRepository;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.common.event.EventEnvelopeValidator;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.common.event.ValidatedEvent;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(AdminServiceStateTest.TestConfig.class)
class AdminServiceStateTest {
    @Autowired
    @Qualifier("normalService")
    private AdminService service;
    @Autowired
    @Qualifier("failingService")
    private AdminService failingService;
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private AdminOpsQueryService opsQueryService;
    @Autowired
    private OutboxFleetHealthService fleetHealthService;

    @BeforeEach
    void setUp() {
        jdbcClient.sql("DROP ALL OBJECTS").update();
        jdbcClient.sql("""
                CREATE TABLE post (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    author_id BIGINT NOT NULL,
                    title VARCHAR(160) NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    version BIGINT NOT NULL DEFAULT 0,
                    review_reason VARCHAR(512) NULL,
                    submitted_at TIMESTAMP NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """).update();
        jdbcClient.sql("""
                CREATE TABLE moderation_task (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    post_id BIGINT NOT NULL,
                    author_id BIGINT NOT NULL,
                    queue_name VARCHAR(64) NOT NULL,
                    machine_result VARCHAR(64) NOT NULL,
                    risk_level VARCHAR(32) NOT NULL,
                    risk_score DOUBLE NOT NULL,
                    reason VARCHAR(512) NOT NULL,
                    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                    review_level VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
                    sla_deadline TIMESTAMP NOT NULL,
                    reviewer_id BIGINT NULL,
                    reviewed_at TIMESTAMP NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """).update();
        jdbcClient.sql("""
                CREATE TABLE moderation_decision (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    task_id BIGINT NOT NULL,
                    post_id BIGINT NOT NULL,
                    reviewer_id BIGINT NOT NULL,
                    action VARCHAR(48) NOT NULL,
                    reason VARCHAR(512) NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """).update();
        jdbcClient.sql("""
                CREATE TABLE post_audit_record (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    post_id BIGINT NOT NULL,
                    auditor_id BIGINT NULL,
                    action VARCHAR(48) NOT NULL,
                    reason VARCHAR(512) NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """).update();
        jdbcClient.sql("""
                CREATE TABLE appeal_record (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    post_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    reason VARCHAR(512) NOT NULL,
                    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                    handler_id BIGINT NULL,
                    handled_at TIMESTAMP NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """).update();
        jdbcClient.sql("""
                CREATE TABLE audit_log (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT NULL,
                    action VARCHAR(96) NOT NULL,
                    target_type VARCHAR(64) NOT NULL,
                    target_id VARCHAR(96) NULL,
                    ip VARCHAR(64) NULL,
                    trace_id VARCHAR(128) NULL,
                    metadata JSON NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """).update();
        jdbcClient.sql("""
                CREATE TABLE outbox_event (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    event_id CHAR(36) NOT NULL UNIQUE,
                    topic VARCHAR(128) NOT NULL,
                    aggregate_type VARCHAR(64) NOT NULL,
                    aggregate_id VARCHAR(64) NOT NULL,
                    payload JSON NOT NULL,
                    status VARCHAR(32) NOT NULL DEFAULT 'NEW',
                    attempts INT NOT NULL DEFAULT 0,
                    next_attempt_at TIMESTAMP NULL,
                    locked_at TIMESTAMP NULL,
                    locked_by VARCHAR(128) NULL,
                    last_error VARCHAR(1024) NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    published_at TIMESTAMP NULL)
                """).update();
        jdbcClient.sql("""
                CREATE TABLE content_idempotency_record (
                    idempotency_key VARCHAR(128) PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    operation VARCHAR(48) NOT NULL,
                    request_fingerprint CHAR(64) NOT NULL,
                    resource_id BIGINT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """).update();
        jdbcClient.sql("""
                CREATE TABLE media_asset (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    status VARCHAR(32) NOT NULL DEFAULT 'PENDING_REVIEW')
                """).update();
        jdbcClient.sql("CREATE TABLE post_media(post_id BIGINT NOT NULL, media_id BIGINT NOT NULL, PRIMARY KEY(post_id, media_id))")
                .update();
        jdbcClient.sql("""
                CREATE TABLE user_risk_profile (
                    user_id BIGINT PRIMARY KEY,
                    status VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
                    risk_score DOUBLE NOT NULL DEFAULT 0,
                    reason VARCHAR(512) NOT NULL DEFAULT '',
                    restricted_until TIMESTAMP NULL,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """).update();
        jdbcClient.sql("""
                CREATE TABLE recommend_experiment (
                    experiment_id VARCHAR(64) PRIMARY KEY,
                    name VARCHAR(120) NOT NULL,
                    scene VARCHAR(32) NOT NULL,
                    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
                    traffic_percent INT NOT NULL DEFAULT 0,
                    weights JSON NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """).update();
        jdbcClient.sql("CREATE TABLE recommendation_impression (id BIGINT AUTO_INCREMENT PRIMARY KEY)").update();
        jdbcClient.sql("CREATE TABLE recommend_event_log (id BIGINT AUTO_INCREMENT PRIMARY KEY)").update();
        jdbcClient.sql("""
                CREATE TABLE tag (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    name VARCHAR(64) NOT NULL UNIQUE,
                    use_count BIGINT NOT NULL DEFAULT 0)
                """).update();
    }

    @Test
    void operationsHealthSurfacesDeadAndAggregateBlockedOutboxEvents() {
        outbox("post.submitted", "DEAD", null, 60);
        outbox("post.submitted", "NEW", null, 50);
        outbox("appeal.created", "FAILED", null, 40);
        when(fleetHealthService.snapshot()).thenReturn(fleet(3, 2, 1, 1, true, "OUTBOX_DEAD"));

        var health = opsQueryService.health();
        var degradation = opsQueryService.degradation();
        var deadEvents = opsQueryService.outboxEvents("DEAD", "post.submitted", 1, 20);

        assertThat(health.database()).isEqualTo("UP");
        assertThat(health.outboxBacklog()).isEqualTo(3);
        assertThat(health.outboxRetryable()).isEqualTo(2);
        assertThat(health.outboxDead()).isEqualTo(1);
        assertThat(health.outboxBlockedByDead()).isEqualTo(1);
        assertThat(health.outboxScope()).isEqualTo("FLEET");
        assertThat(health.degraded()).isTrue();
        assertThat(health.degradationReason()).isEqualTo("OUTBOX_DEAD");
        assertThat(degradation.degraded()).isTrue();
        assertThat(degradation.reason()).isEqualTo("OUTBOX_DEAD");
        assertThat(degradation.outboxBacklog()).isEqualTo(3);
        assertThat(degradation.outboxDead()).isEqualTo(1);
        assertThat(degradation.outboxBlockedByDead()).isEqualTo(1);
        assertThat(deadEvents).singleElement().satisfies(event -> assertThat(event)
                .hasFieldOrPropertyWithValue("status", "DEAD")
                .hasFieldOrPropertyWithValue("attempts", 3)
                .hasFieldOrPropertyWithValue("lastError", "previous failure"));
    }

    @Test
    void operationsHealthKeepsAdminDatabaseFailureDistinctFromFleetFailure() {
        jdbcClient.sql("DROP TABLE post").update();
        jdbcClient.sql("DROP TABLE recommendation_impression").update();
        when(fleetHealthService.snapshot()).thenReturn(fleet(1, 0, 1, 0, true, "OUTBOX_DEAD"));

        var health = opsQueryService.health();
        var degradation = opsQueryService.degradation();

        assertThat(health.database()).isEqualTo("DOWN");
        assertThat(health.source()).isEqualTo("HEALTH_CHECK");
        assertThat(health.degraded()).isTrue();
        assertThat(health.degradationReason()).isEqualTo("ADMIN_DATABASE_UNAVAILABLE");
        assertThat(health.outboxDegradationReason()).isEqualTo("OUTBOX_DEAD");
        assertThat(degradation.source()).isEqualTo("HEALTH_CHECK");
        assertThat(degradation.reason()).isEqualTo("DATABASE_UNAVAILABLE");
        assertThat(degradation.outboxDegradationReason()).isEqualTo("OUTBOX_DEAD");
    }

    @Test
    void incompleteFleetTakesPrecedenceOverLocalDeadReason() {
        outbox("post.submitted", "DEAD", null, 60);
        when(fleetHealthService.snapshot()).thenReturn(new OutboxFleetSnapshot(
                "FLEET", false, 1, 0, 1, 0,
                List.of(
                        OutboxSourceView.available("dc-content", 1, 0, 1, 0),
                        OutboxSourceView.unavailable("dc-user")),
                List.of("dc-user"), true, "OUTBOX_SOURCE_UNAVAILABLE"));

        var health = opsQueryService.health();
        var degradation = opsQueryService.degradation();

        assertThat(health.outboxComplete()).isFalse();
        assertThat(health.unavailableOutboxSources()).containsExactly("dc-user");
        assertThat(health.degradationReason()).isEqualTo("OUTBOX_SOURCE_UNAVAILABLE");
        assertThat(degradation.reason()).isEqualTo("OUTBOX_SOURCE_UNAVAILABLE");
    }

    private OutboxFleetSnapshot fleet(long backlog, long retryable, long dead, long blocked,
                                       boolean degraded, String reason) {
        return new OutboxFleetSnapshot("FLEET", true, backlog, retryable, dead, blocked,
                List.of(OutboxSourceView.available("dc-content", backlog, retryable, dead, blocked)),
                List.of(), degraded, reason);
    }

    @Test
    void taskDecisionBindsToRequestedTaskAndCancelsActiveSiblings() {
        long postId = post(7L, "待审核档案", "PENDING");
        long requested = task(postId, 7L, "PENDING");
        long sibling = task(postId, 7L, "ESCALATED");
        long mediaId = media(postId);

        var result = service.approveModerationTask(requested, 9L, "trace-approve",
                "证据与媒体均符合发布要求", "approve-task");
        var replay = service.approveModerationTask(requested, 9L, "trace-approve",
                "证据与媒体均符合发布要求", "approve-task");

        assertThat(result.action()).isEqualTo("APPROVE_POST");
        assertThat(replay.attributes()).containsEntry("duplicateRequest", true)
                .containsEntry("resourceId", requested);
        assertThat(postStatus(postId)).isEqualTo("PUBLISHED");
        assertThat(taskStatus(requested)).isEqualTo("APPROVED");
        assertThat(taskStatus(sibling)).isEqualTo("CANCELLED");
        assertThat(mediaStatus(mediaId)).isEqualTo("APPROVED");
        assertThat(decisionTaskIds()).containsExactly(requested);
        assertThat(count("post_audit_record")).isEqualTo(1);
        assertThat(count("audit_log")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(1);

        assertCode(() -> service.approveModerationTask(requested, 9L, "trace-approve",
                "同一请求键不能更换审核理由", "approve-task"), ErrorCode.DUPLICATE_OPERATION);
        assertThat(count("moderation_decision")).isEqualTo(1);

        assertCode(() -> service.rejectModerationTask(requested, 9L, "trace", "改为驳回", "approve-task"),
                ErrorCode.DUPLICATE_OPERATION);
        assertCode(() -> service.approveModerationTask(sibling, 9L, "trace", "另一个任务", "approve-task"),
                ErrorCode.DUPLICATE_OPERATION);
        assertCode(() -> service.approveModerationTask(requested, 10L, "trace", "另一个审核员", "approve-task"),
                ErrorCode.DUPLICATE_OPERATION);
    }

    @Test
    void moderationOutboxUsesTheSharedVersionedEnvelopeContract() throws Exception {
        long postId = post(17L, "审核事件契约档案", "PENDING");
        long taskId = task(postId, 17L, "PENDING");

        service.approveModerationTask(taskId, 9L, "trace-event-contract",
                "事件载荷需要能够被下游严格解析", "approve-event-contract");

        Map<String, Object> row = jdbcClient.sql("""
                        SELECT event_id, topic, aggregate_type, aggregate_id, payload
                        FROM outbox_event
                        """)
                .query((rs, rowNum) -> Map.<String, Object>of(
                        "eventId", rs.getString("event_id"),
                        "topic", rs.getString("topic"),
                        "aggregateType", rs.getString("aggregate_type"),
                        "aggregateId", rs.getString("aggregate_id"),
                        "payload", rs.getString("payload")))
                .single();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String storedPayload = String.valueOf(row.get("payload"));
        var storedJson = mapper.readTree(storedPayload);
        String message = storedJson.isTextual() ? storedJson.textValue() : storedPayload;
        ValidatedEvent event = new EventEnvelopeValidator(mapper)
                .parse(message, Map.of(EventTopics.AUDIT_COMPLETED, java.util.Set.of("dc-admin")));

        assertThat(row).containsEntry("eventId", event.eventId().toString())
                .containsEntry("topic", EventTopics.AUDIT_COMPLETED)
                .containsEntry("aggregateType", "POST_AUDIT")
                .containsEntry("aggregateId", String.valueOf(postId));
        assertThat(event.version()).isEqualTo(1);
        assertThat(event.producer()).isEqualTo("dc-admin");
        assertThat(event.aggregateId()).isEqualTo(String.valueOf(postId));
        assertThat(event.payload().path("postId").asLong()).isEqualTo(postId);
        assertThat(event.payload().path("receiverId").asLong()).isEqualTo(17L);
        assertThat(event.payload().path("action").asText()).isEqualTo("APPROVE_POST");
        assertThat(event.payload().path("status").asText()).isEqualTo("PUBLISHED");
    }

    @Test
    void serializationFailureRollsBackPostTaskMediaDecisionKeyAndOutbox() {
        long postId = post(7L, "事务回滚档案", "PENDING");
        long taskId = task(postId, 7L, "PENDING");
        long mediaId = media(postId);

        assertThatThrownBy(() -> failingService.approveModerationTask(taskId, 9L, "trace-failure",
                "序列化失败必须整体回滚", "failure-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("serialize audit metadata");

        assertThat(postStatus(postId)).isEqualTo("PENDING");
        assertThat(taskStatus(taskId)).isEqualTo("PENDING");
        assertThat(mediaStatus(mediaId)).isEqualTo("PENDING_REVIEW");
        assertThat(count("moderation_decision")).isZero();
        assertThat(count("post_audit_record")).isZero();
        assertThat(count("audit_log")).isZero();
        assertThat(count("outbox_event")).isZero();
        assertThat(idempotencyCount("failure-key")).isZero();

        service.approveModerationTask(taskId, 9L, "trace-retry",
                "故障恢复后允许同键重试", "failure-key");
        assertThat(postStatus(postId)).isEqualTo("PUBLISHED");
    }

    @Test
    void directPostDecisionSelectsLatestTaskAndCancelsTheOlderTask() {
        long postId = post(7L, "直接审核档案", "PENDING");
        long older = task(postId, 7L, "PENDING");
        long latest = task(postId, 7L, "PENDING");

        service.approve(postId, 9L, "trace-direct", "直接审核选择最新活动任务", "direct-approve");

        assertThat(taskStatus(older)).isEqualTo("CANCELLED");
        assertThat(taskStatus(latest)).isEqualTo("APPROVED");
        assertThat(decisionTaskIds()).containsExactly(latest);
        assertThat(postStatus(postId)).isEqualTo("PUBLISHED");
    }

    @Test
    void requireChangesPersistsReasonAndCancelsSiblingTasks() {
        long postId = post(7L, "需要修改的档案", "PENDING");
        long requested = task(postId, 7L, "PENDING");
        long sibling = task(postId, 7L, "ESCALATED");

        service.requireChanges(requested, 9L, "trace-changes",
                "时间线与证据图的对应关系需要补充", "require-changes");

        assertThat(postStatus(postId)).isEqualTo("CHANGES_REQUIRED");
        assertThat(postReviewReason(postId)).isEqualTo("时间线与证据图的对应关系需要补充");
        assertThat(taskStatus(requested)).isEqualTo("CHANGES_REQUIRED");
        assertThat(taskStatus(sibling)).isEqualTo("CANCELLED");
        assertThat(decisionTaskIds()).containsExactly(requested);
        assertThat(count("post_audit_record")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(1);
    }

    @Test
    void rejectionKeepsMediaPrivateAndPublishesOneRejectionDecision() {
        long postId = post(7L, "审核驳回档案", "PENDING");
        long requested = task(postId, 7L, "PENDING");
        long sibling = task(postId, 7L, "ESCALATED");
        long mediaId = media(postId);

        service.rejectModerationTask(requested, 9L, "trace-reject-task",
                "关键证据来源无法核验", "reject-task");

        assertThat(postStatus(postId)).isEqualTo("REJECTED");
        assertThat(postReviewReason(postId)).isEqualTo("关键证据来源无法核验");
        assertThat(taskStatus(requested)).isEqualTo("REJECTED");
        assertThat(taskStatus(sibling)).isEqualTo("CANCELLED");
        assertThat(mediaStatus(mediaId)).isEqualTo("PENDING_REVIEW");
        assertThat(decisionTaskIds()).containsExactly(requested);
        assertThat(count("post_audit_record")).isEqualTo(1);
        assertThat(count("audit_log")).isEqualTo(1);
        assertThat(outboxTopics()).containsExactly("content.rejected");
    }

    @Test
    void escalationIsSingleStepAndRequiresAPendingPost() {
        long postId = post(7L, "升级复审档案", "PENDING");
        long taskId = task(postId, 7L, "PENDING");

        service.escalateModerationTask(taskId, 9L, "trace-escalate",
                "风险信号需要高级审核员复核", "escalate-once");

        assertThat(postStatus(postId)).isEqualTo("PENDING");
        assertThat(taskStatus(taskId)).isEqualTo("ESCALATED");
        assertThat(decisionTaskIds()).containsExactly(taskId);
        assertThat(count("audit_log")).isEqualTo(1);
        assertThat(count("outbox_event")).isZero();

        assertCode(() -> service.escalateModerationTask(taskId, 9L, "trace-escalate-again",
                "不能重复升级", "escalate-again"), ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(idempotencyCount("escalate-again")).isZero();
        assertThat(count("moderation_decision")).isEqualTo(1);

        service.requireChanges(taskId, 9L, "trace-after-escalate",
                "高级复核确认需要作者补充来源", "changes-after-escalate");
        assertThat(postStatus(postId)).isEqualTo("CHANGES_REQUIRED");
        assertThat(taskStatus(taskId)).isEqualTo("CHANGES_REQUIRED");
        assertThat(count("moderation_decision")).isEqualTo(2);

        long publishedPost = post(8L, "已发布但残留任务", "PUBLISHED");
        long staleTask = task(publishedPost, 8L, "PENDING");
        assertCode(() -> service.escalateModerationTask(staleTask, 9L, "trace-stale",
                "不应升级已发布投稿", "stale-escalate"), ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(taskStatus(staleTask)).isEqualTo("PENDING");
        assertThat(idempotencyCount("stale-escalate")).isZero();
    }

    @Test
    void databaseFailureDuringOutboxWriteRollsBackTheWholeDecision() {
        long postId = post(7L, "数据库故障档案", "PENDING");
        long taskId = task(postId, 7L, "PENDING");
        jdbcClient.sql("DROP TABLE outbox_event").update();

        assertThatThrownBy(() -> service.approveModerationTask(taskId, 9L, "trace-db-failure",
                "Outbox 写入失败必须整体回滚", "db-failure-key"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.code()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);
                    assertThat(error.getMessage()).isEqualTo("运营数据暂时不可用");
                    assertThat(error.getCause()).isInstanceOf(DataAccessException.class);
                    assertThat(error.getMessage()).doesNotContain("outbox_event", "SQL");
                });

        assertThat(postStatus(postId)).isEqualTo("PENDING");
        assertThat(taskStatus(taskId)).isEqualTo("PENDING");
        assertThat(count("moderation_decision")).isZero();
        assertThat(count("post_audit_record")).isZero();
        assertThat(count("audit_log")).isZero();
        assertThat(idempotencyCount("db-failure-key")).isZero();
    }

    @Test
    void invalidReasonIdentityOrMissingTaskNeverBurnsTheRequestKey() {
        long postId = post(7L, "审核校验档案", "PENDING");
        long taskId = task(postId, 7L, "PENDING");

        assertCode(() -> service.approveModerationTask(taskId, 9L, "trace", " ", "reason-key"),
                ErrorCode.VALIDATION_FAILED);
        assertThat(idempotencyCount("reason-key")).isZero();
        assertCode(() -> service.escalateModerationTask(taskId, 9L, "trace", null, "escalate-reason-key"),
                ErrorCode.VALIDATION_FAILED);
        assertThat(idempotencyCount("escalate-reason-key")).isZero();
        assertCode(() -> service.requireChanges(taskId, 9L, "trace", " ", "changes-reason-key"),
                ErrorCode.VALIDATION_FAILED);
        assertThat(idempotencyCount("changes-reason-key")).isZero();
        assertThat(taskStatus(taskId)).isEqualTo("PENDING");
        assertCode(() -> service.approveModerationTask(taskId, 9L, "trace", "x".repeat(513), "long-reason-key"),
                ErrorCode.VALIDATION_FAILED);
        assertThat(idempotencyCount("long-reason-key")).isZero();
        assertCode(() -> service.approveModerationTask(taskId, null, "trace", "理由有效", "identity-key"),
                ErrorCode.UNAUTHORIZED);
        assertThat(idempotencyCount("identity-key")).isZero();
        assertCode(() -> service.approveModerationTask(0L, 9L, "trace", "任务无效", "invalid-task-key"),
                ErrorCode.VALIDATION_FAILED);
        assertThat(idempotencyCount("invalid-task-key")).isZero();
        assertCode(() -> service.approveModerationTask(9999L, 9L, "trace", "任务不存在", "missing-key"),
                ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(idempotencyCount("missing-key")).isZero();

        service.approveModerationTask(taskId, 9L, "trace", "修复输入后允许重试", "reason-key");
        assertThat(postStatus(postId)).isEqualTo("PUBLISHED");
    }

    @Test
    void acceptedAppealRequeuesPostAndRejectedAppealLeavesPostRejected() {
        long acceptedPost = post(21L, "申诉通过档案", "CHANGES_REQUIRED");
        long acceptedAppeal = appeal(acceptedPost, 21L, "证据已补充");

        var accepted = service.resolveAppeal(acceptedAppeal, 9L, "trace-appeal",
                "补充材料有效，进入人工复审", true, "accept-appeal");
        var acceptedReplay = service.resolveAppeal(acceptedAppeal, 9L, "trace-appeal",
                "补充材料有效，进入人工复审", true, "accept-appeal");

        assertThat(accepted.action()).isEqualTo("APPEAL_APPROVED");
        assertThat(acceptedReplay.attributes()).containsEntry("duplicateRequest", true);
        assertThat(postStatus(acceptedPost)).isEqualTo("PENDING");
        assertThat(appealStatus(acceptedAppeal)).isEqualTo("APPROVED");
        assertThat(activeTaskCount(acceptedPost)).isEqualTo(1);

        long rejectedPost = post(22L, "申诉驳回档案", "REJECTED");
        long rejectedAppeal = appeal(rejectedPost, 22L, "希望再次复核");
        service.resolveAppeal(rejectedAppeal, 9L, "trace-reject",
                "原审核结论成立", false, "reject-appeal");

        assertThat(postStatus(rejectedPost)).isEqualTo("REJECTED");
        assertThat(appealStatus(rejectedAppeal)).isEqualTo("REJECTED");
        assertThat(activeTaskCount(rejectedPost)).isZero();
        assertThat(count("post_audit_record")).isEqualTo(2);
        assertThat(count("outbox_event")).isEqualTo(2);
    }

    @Test
    void appealStatusFailureRollsBackKeyAndCanBeRetriedAfterRepair() {
        long postId = post(31L, "状态修复档案", "PUBLISHED");
        long appealId = appeal(postId, 31L, "状态暂时不允许");

        assertCode(() -> service.resolveAppeal(appealId, 9L, "trace", "先修复状态", true, "repair-key"),
                ErrorCode.VALIDATION_FAILED);
        assertThat(idempotencyCount("repair-key")).isZero();
        assertThat(appealStatus(appealId)).isEqualTo("PENDING");

        jdbcClient.sql("UPDATE post SET status = 'REJECTED' WHERE id = :id").param("id", postId).update();
        service.resolveAppeal(appealId, 9L, "trace", "状态修复后重试", true, "repair-key");
        assertThat(postStatus(postId)).isEqualTo("PENDING");
    }

    @Test
    void appealIdempotencyKeyCannotChangeDecisionReasonOrActor() {
        long postId = post(32L, "申诉幂等档案", "CHANGES_REQUIRED");
        long appealId = appeal(postId, 32L, "补充证据已经提交");

        var result = service.resolveAppeal(appealId, 9L, "trace-appeal-idempotency",
                "证据有效，重新进入人工审核", true, "appeal-idempotency-key");
        var replay = service.resolveAppeal(appealId, 9L, "trace-appeal-idempotency",
                "证据有效，重新进入人工审核", true, "appeal-idempotency-key");

        assertThat(result.action()).isEqualTo("APPEAL_APPROVED");
        assertThat(replay.attributes()).containsEntry("duplicateRequest", true)
                .containsEntry("resourceId", appealId);

        assertCode(() -> service.resolveAppeal(appealId, 9L, "trace",
                "替换理由不能复用请求键", true, "appeal-idempotency-key"), ErrorCode.DUPLICATE_OPERATION);
        assertCode(() -> service.resolveAppeal(appealId, 9L, "trace",
                "同一请求键不能改变审核结论", false, "appeal-idempotency-key"), ErrorCode.DUPLICATE_OPERATION);
        assertCode(() -> service.resolveAppeal(appealId, 10L, "trace",
                "其他审核员不能接管请求键", true, "appeal-idempotency-key"), ErrorCode.DUPLICATE_OPERATION);

        assertThat(postStatus(postId)).isEqualTo("PENDING");
        assertThat(appealStatus(appealId)).isEqualTo("APPROVED");
        assertThat(count("post_audit_record")).isEqualTo(1);
        assertThat(count("audit_log")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(1);
        assertThat(idempotencyCount("appeal-idempotency-key")).isEqualTo(1);
    }

    @Test
    void appealAuditFailureRollsBackAllStateAndAllowsSameKeyRetry() {
        long postId = post(33L, "申诉事务档案", "REJECTED");
        long appealId = appeal(postId, 33L, "新增来源可以核验");

        assertThatThrownBy(() -> failingService.resolveAppeal(appealId, 9L, "trace-appeal-failure",
                "审计不可用时必须整体回滚", true, "appeal-failure-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("serialize audit metadata");

        assertThat(postStatus(postId)).isEqualTo("REJECTED");
        assertThat(appealStatus(appealId)).isEqualTo("PENDING");
        assertThat(activeTaskCount(postId)).isZero();
        assertThat(count("moderation_decision")).isZero();
        assertThat(count("post_audit_record")).isZero();
        assertThat(count("audit_log")).isZero();
        assertThat(count("outbox_event")).isZero();
        assertThat(idempotencyCount("appeal-failure-key")).isZero();

        service.resolveAppeal(appealId, 9L, "trace-appeal-retry",
                "依赖恢复后允许使用原键重试", true, "appeal-failure-key");
        assertThat(postStatus(postId)).isEqualTo("PENDING");
        assertThat(appealStatus(appealId)).isEqualTo("APPROVED");
        assertThat(activeTaskCount(postId)).isEqualTo(1);
        assertThat(count("post_audit_record")).isEqualTo(1);
        assertThat(count("audit_log")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(1);
    }

    @Test
    void restrictionDefaultsSafelyAndReplaysWithoutDuplicateMutation() {
        var result = service.restrictUser(41L, 9L, "trace-restrict", null,
                "连续发布无法核验来源的内容", "restrict-user-41");
        var replay = service.restrictUser(41L, 9L, "trace-restrict", null,
                "连续发布无法核验来源的内容", "restrict-user-41");

        assertThat(result.attributes()).containsEntry("userId", 41L).containsEntry("status", "NO_POST");
        assertThat(replay.attributes()).containsEntry("duplicateRequest", true).containsEntry("resourceId", 41L);
        assertThat(riskStatus(41L)).isEqualTo("NO_POST");
        assertThat(riskReason(41L)).isEqualTo("连续发布无法核验来源的内容");
        assertThat(count("user_risk_profile")).isEqualTo(1);
        assertThat(count("audit_log")).isEqualTo(1);
        assertThat(auditUser("RESTRICT_USER")).isEqualTo(9L);
        assertThat(idempotencyCount("restrict-user-41")).isEqualTo(1);

        assertCode(() -> service.restrictUser(41L, 9L, "trace-restrict", "BANNED",
                "同一请求键不能更换治理状态", "restrict-user-41"), ErrorCode.DUPLICATE_OPERATION);
        assertThat(riskStatus(41L)).isEqualTo("NO_POST");
        assertThat(count("audit_log")).isEqualTo(1);

        assertCode(() -> service.unrestrictUser(41L, 9L, "trace-unrestrict",
                "不能复用限制操作的请求键", "restrict-user-41"), ErrorCode.DUPLICATE_OPERATION);
        assertThat(riskStatus(41L)).isEqualTo("NO_POST");
    }

    @Test
    void invalidRestrictionInputNeverBurnsTheRequestKey() {
        assertCode(() -> service.restrictUser(42L, 9L, "trace", "UNKNOWN", "理由有效", "bad-status"),
                ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.restrictUser(42L, 9L, "trace", "NO_POST", " ", "bad-reason"),
                ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.restrictUser(0L, 9L, "trace", "NO_POST", "理由有效", "bad-user"),
                ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.restrictUser(42L, null, "trace", "NO_POST", "理由有效", "bad-auditor"),
                ErrorCode.UNAUTHORIZED);

        assertThat(count("user_risk_profile")).isZero();
        assertThat(idempotencyCount("bad-status")).isZero();
        assertThat(idempotencyCount("bad-reason")).isZero();
        assertThat(idempotencyCount("bad-user")).isZero();
        assertThat(idempotencyCount("bad-auditor")).isZero();

        var repaired = service.restrictUser(42L, 9L, "trace", " watch ", "修复输入后重试", "bad-status");
        assertThat(repaired.attributes()).containsEntry("status", "WATCH");
    }

    @Test
    void auditStorageFailureRollsBackUnrestrictionAndRequestKey() {
        service.restrictUser(43L, 9L, "trace-restrict", "NO_POST", "暂时限制投稿", "restrict-user-43");
        jdbcClient.sql("DROP TABLE audit_log").update();

        assertThatThrownBy(() -> service.unrestrictUser(43L, 9L, "trace-unrestrict",
                "复核通过解除限制", "unrestrict-user-43"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.code()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);
                    assertThat(error.getCause()).isInstanceOf(DataAccessException.class);
                });

        assertThat(riskStatus(43L)).isEqualTo("NO_POST");
        assertThat(idempotencyCount("unrestrict-user-43")).isZero();
    }

    @Test
    void experimentWritesAreValidatedAuditedAndBoundToTheirPayloads() {
        Map<String, Object> payload = Map.of(
                "experimentId", "exp-home-a",
                "name", "首页主题均衡实验",
                "scene", "home",
                "weights", Map.of("theme", 35, "follow", 20, "quality", 20, "freshness", 15, "exploration", 10));

        var created = service.createExperiment(payload, 9L, "trace-create-exp", "create-exp");
        var replay = service.createExperiment(payload, 9L, "trace-create-exp", "create-exp");

        assertThat(created.attributes()).containsEntry("experimentId", "exp-home-a")
                .containsEntry("status", "DRAFT");
        assertThat(replay.attributes()).containsEntry("experimentId", "exp-home-a")
                .containsEntry("status", "DRAFT")
                .containsEntry("duplicateRequest", true);
        assertThat(count("recommend_experiment")).isEqualTo(1);
        assertThat(count("audit_log")).isEqualTo(1);
        assertThat(auditUser("CREATE_RECOMMEND_EXPERIMENT")).isEqualTo(9L);
        assertThat(idempotencyCount("create-exp")).isEqualTo(1);

        assertCode(() -> service.createExperiment(Map.of(
                "experimentId", "exp-home-a", "name", "不能覆盖既有实验", "scene", "discover",
                "weights", Map.of("theme", 1)), 9L, "trace", "create-exp"), ErrorCode.DUPLICATE_OPERATION);
        assertCode(() -> service.createExperiment(payload, 9L, "trace", "duplicate-exp-id"),
                ErrorCode.DUPLICATE_OPERATION);
        assertThat(idempotencyCount("duplicate-exp-id")).isZero();

        var active = service.updateExperimentStatus("exp-home-a", "ACTIVE", 9L,
                "trace-active", "activate-exp");
        var activeReplay = service.updateExperimentStatus("exp-home-a", "ACTIVE", 9L,
                "trace-active", "activate-exp");
        assertThat(active.attributes()).containsEntry("status", "ACTIVE");
        assertThat(activeReplay.attributes()).containsEntry("experimentId", "exp-home-a")
                .containsEntry("status", "ACTIVE")
                .containsEntry("duplicateRequest", true);
        assertThat(experimentStatus("exp-home-a")).isEqualTo("ACTIVE");
        assertThat(experimentTraffic("exp-home-a")).isEqualTo(100);

        assertCode(() -> service.updateExperimentStatus("exp-home-a", "PAUSED", 9L,
                "trace", "activate-exp"), ErrorCode.DUPLICATE_OPERATION);
        service.updateExperimentStatus("exp-home-a", "PAUSED", 9L,
                "trace-pause", "pause-exp");
        assertThat(experimentStatus("exp-home-a")).isEqualTo("PAUSED");
        assertThat(experimentTraffic("exp-home-a")).isZero();
        assertThat(count("audit_log")).isEqualTo(3);
    }

    @Test
    void invalidOrFailedExperimentWritesNeverBurnTheirRequestKeys() {
        assertCode(() -> service.createExperiment(Map.of(
                "experimentId", "!", "weights", Map.of("theme", 1)), 9L, "trace", "bad-exp-id"),
                ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.createExperiment(Map.of(
                "experimentId", "exp-bad-weight", "weights", Map.of("theme", "many")),
                9L, "trace", "bad-exp-weight"), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.updateExperimentStatus("missing-exp", "ACTIVE", 9L,
                "trace", "missing-exp-key"), ErrorCode.RESOURCE_NOT_FOUND);
        assertCode(() -> service.updateExperimentStatus("missing-exp", "DELETED", 9L,
                "trace", "bad-exp-status"), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.createExperiment(Map.of("experimentId", "exp-no-actor"),
                null, "trace", "no-actor"), ErrorCode.UNAUTHORIZED);

        assertThat(idempotencyCount("bad-exp-id")).isZero();
        assertThat(idempotencyCount("bad-exp-weight")).isZero();
        assertThat(idempotencyCount("missing-exp-key")).isZero();
        assertThat(idempotencyCount("bad-exp-status")).isZero();
        assertThat(idempotencyCount("no-actor")).isZero();

        jdbcClient.sql("DROP TABLE audit_log").update();
        assertThatThrownBy(() -> service.createExperiment(Map.of(
                "experimentId", "exp-audit-failure", "weights", Map.of("theme", 1)),
                9L, "trace", "audit-failure-exp"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE));
        assertThat(count("recommend_experiment")).isZero();
        assertThat(idempotencyCount("audit-failure-exp")).isZero();
    }

    @Test
    void outboxReplayOnlyRequeuesEligibleEventsAndIsIdempotent() {
        long failed = outbox("post.submitted", "FAILED", null, 60);
        long dead = outbox("post.submitted", "DEAD", null, 50);
        long expired = outbox("post.submitted", "PROCESSING", -10, 40);
        long fresh = outbox("post.submitted", "PROCESSING", -1, 30);
        long otherTopic = outbox("appeal.created", "FAILED", null, 20);
        long alreadyNew = outbox("post.submitted", "NEW", null, 10);

        var result = service.replayOutbox("post.submitted", 2, 9L, "trace-replay", "replay-key");
        var replay = service.replayOutbox("post.submitted", 2, 9L, "trace-replay", "replay-key");

        assertThat(result.attributes()).containsEntry("affected", 2).containsEntry("topic", "post.submitted");
        assertThat(replay.attributes()).containsEntry("topic", "post.submitted")
                .containsEntry("duplicateRequest", true);
        assertThat(outboxStatus(failed)).isEqualTo("NEW");
        assertThat(outboxStatus(dead)).isEqualTo("NEW");
        assertThat(outboxStatus(expired)).isEqualTo("PROCESSING");
        assertThat(outboxStatus(fresh)).isEqualTo("PROCESSING");
        assertThat(outboxStatus(otherTopic)).isEqualTo("FAILED");
        assertThat(outboxStatus(alreadyNew)).isEqualTo("NEW");
        assertThat(count("audit_log")).isEqualTo(1);
        assertThat(auditUser("REPLAY_OUTBOX")).isEqualTo(9L);
        assertThat(idempotencyCount("replay-key")).isEqualTo(1);

        assertCode(() -> service.replayOutbox("post.submitted", 3, 9L,
                "trace", "replay-key"), ErrorCode.DUPLICATE_OPERATION);
    }

    @Test
    void outboxReplayAuditFailureRollsBackStatusAndRequestKey() {
        long failed = outbox("post.submitted", "FAILED", null, 10);
        jdbcClient.sql("DROP TABLE audit_log").update();

        assertThatThrownBy(() -> service.replayOutbox("post.submitted", 10, 9L,
                "trace", "replay-audit-failure"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE));

        assertThat(outboxStatus(failed)).isEqualTo("FAILED");
        assertThat(idempotencyCount("replay-audit-failure")).isZero();
    }

    @Test
    void tagCreationUsesRealInputWithoutInflatingUsageAndRollsBackOnAuditFailure() {
        var created = service.createTag("  轨道档案  ", 9L, "trace-tag", "tag-create");
        var replay = service.createTag("轨道档案", 9L, "trace-tag", "tag-create");

        assertThat(created.attributes()).containsEntry("name", "轨道档案").containsEntry("created", true);
        assertThat(replay.attributes()).containsEntry("name", "轨道档案")
                .containsEntry("duplicateRequest", true);
        assertThat(count("tag")).isEqualTo(1);
        assertThat(tagUseCount("轨道档案")).isZero();
        assertThat(count("audit_log")).isEqualTo(1);
        assertThat(auditUser("CREATE_TAG")).isEqualTo(9L);

        assertCode(() -> service.createTag("另一个标签", 9L, "trace", "tag-create"),
                ErrorCode.DUPLICATE_OPERATION);
        var existing = service.createTag("轨道档案", 9L, "trace-existing", "tag-existing");
        assertThat(existing.attributes()).containsEntry("created", false);
        assertThat(count("tag")).isEqualTo(1);
        assertThat(tagUseCount("轨道档案")).isZero();
        assertThat(count("audit_log")).isEqualTo(2);

        assertCode(() -> service.createTag(" ", 9L, "trace", "tag-empty"), ErrorCode.VALIDATION_FAILED);
        assertThat(idempotencyCount("tag-empty")).isZero();

        jdbcClient.sql("DROP TABLE audit_log").update();
        assertThatThrownBy(() -> service.createTag("审计失败标签", 9L,
                "trace-failure", "tag-audit-failure"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE));
        assertThat(count("tag")).isEqualTo(1);
        assertThat(idempotencyCount("tag-audit-failure")).isZero();
    }

    private long post(long authorId, String title, String status) {
        jdbcClient.sql("INSERT INTO post(author_id, title, status) VALUES (:authorId, :title, :status)")
                .param("authorId", authorId).param("title", title).param("status", status).update();
        return jdbcClient.sql("SELECT MAX(id) FROM post").query(Long.class).single();
    }

    private long task(long postId, long authorId, String status) {
        jdbcClient.sql("""
                        INSERT INTO moderation_task(post_id, author_id, queue_name, machine_result, risk_level,
                                                    risk_score, reason, status, review_level, sla_deadline)
                        VALUES (:postId, :authorId, 'standard', 'PASS', 'LOW', 0, 'machine', :status,
                                'NORMAL', TIMESTAMPADD(HOUR, 24, CURRENT_TIMESTAMP))
                        """)
                .param("postId", postId).param("authorId", authorId).param("status", status).update();
        return jdbcClient.sql("SELECT MAX(id) FROM moderation_task").query(Long.class).single();
    }

    private long appeal(long postId, long userId, String reason) {
        jdbcClient.sql("INSERT INTO appeal_record(post_id, user_id, reason) VALUES (:postId, :userId, :reason)")
                .param("postId", postId).param("userId", userId).param("reason", reason).update();
        return jdbcClient.sql("SELECT MAX(id) FROM appeal_record").query(Long.class).single();
    }

    private long media(long postId) {
        jdbcClient.sql("INSERT INTO media_asset(status) VALUES ('PENDING_REVIEW')").update();
        long mediaId = jdbcClient.sql("SELECT MAX(id) FROM media_asset").query(Long.class).single();
        jdbcClient.sql("INSERT INTO post_media(post_id, media_id) VALUES (:postId, :mediaId)")
                .param("postId", postId).param("mediaId", mediaId).update();
        return mediaId;
    }

    private long outbox(String topic, String status, Integer lockedMinutes, int ageSeconds) {
        jdbcClient.sql("""
                        INSERT INTO outbox_event(event_id, topic, aggregate_type, aggregate_id, payload,
                                                 status, attempts, locked_at, locked_by, last_error, created_at)
                        VALUES (:eventId, :topic, 'POST', '1', '{}', :status, 3,
                                CASE WHEN :lockedMinutes IS NULL THEN NULL
                                     ELSE TIMESTAMPADD(MINUTE, :lockedMinutes, CURRENT_TIMESTAMP) END,
                                'worker-1', 'previous failure',
                                TIMESTAMPADD(SECOND, :ageOffset, CURRENT_TIMESTAMP))
                        """)
                .param("eventId", UUID.randomUUID().toString()).param("topic", topic).param("status", status)
                .param("lockedMinutes", lockedMinutes).param("ageOffset", -ageSeconds).update();
        return jdbcClient.sql("SELECT MAX(id) FROM outbox_event").query(Long.class).single();
    }

    private String postStatus(long postId) {
        return value("SELECT status FROM post WHERE id = :id", postId);
    }

    private String postReviewReason(long postId) {
        return value("SELECT review_reason FROM post WHERE id = :id", postId);
    }

    private String taskStatus(long taskId) {
        return value("SELECT status FROM moderation_task WHERE id = :id", taskId);
    }

    private String appealStatus(long appealId) {
        return value("SELECT status FROM appeal_record WHERE id = :id", appealId);
    }

    private String mediaStatus(long mediaId) {
        return value("SELECT status FROM media_asset WHERE id = :id", mediaId);
    }

    private String experimentStatus(String experimentId) {
        return jdbcClient.sql("SELECT status FROM recommend_experiment WHERE experiment_id = :id")
                .param("id", experimentId).query(String.class).single();
    }

    private int experimentTraffic(String experimentId) {
        return jdbcClient.sql("SELECT traffic_percent FROM recommend_experiment WHERE experiment_id = :id")
                .param("id", experimentId).query(Integer.class).single();
    }

    private long tagUseCount(String name) {
        return jdbcClient.sql("SELECT use_count FROM tag WHERE name = :name")
                .param("name", name).query(Long.class).single();
    }

    private String outboxStatus(long id) {
        return value("SELECT status FROM outbox_event WHERE id = :id", id);
    }

    private String value(String sql, long id) {
        return jdbcClient.sql(sql).param("id", id).query(String.class).single();
    }

    private List<Long> decisionTaskIds() {
        return jdbcClient.sql("SELECT task_id FROM moderation_decision ORDER BY id")
                .query(Long.class).list();
    }

    private List<String> outboxTopics() {
        return jdbcClient.sql("SELECT topic FROM outbox_event ORDER BY id")
                .query(String.class).list();
    }

    private long activeTaskCount(long postId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM moderation_task WHERE post_id = :postId AND status = 'PENDING'")
                .param("postId", postId).query(Long.class).single();
    }

    private long count(String table) {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    private long idempotencyCount(String key) {
        return jdbcClient.sql("SELECT COUNT(*) FROM content_idempotency_record WHERE idempotency_key = :key")
                .param("key", key).query(Long.class).single();
    }

    private String riskStatus(long userId) {
        return jdbcClient.sql("SELECT status FROM user_risk_profile WHERE user_id = :userId")
                .param("userId", userId).query(String.class).single();
    }

    private String riskReason(long userId) {
        return jdbcClient.sql("SELECT reason FROM user_risk_profile WHERE user_id = :userId")
                .param("userId", userId).query(String.class).single();
    }

    private long auditUser(String action) {
        return jdbcClient.sql("SELECT user_id FROM audit_log WHERE action = :action")
                .param("action", action).query(Long.class).single();
    }

    private void assertCode(ThrowingAction action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(expected));
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:admin-state;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
            return dataSource;
        }

        @Bean
        JdbcClient jdbcClient(DataSource dataSource) {
            return JdbcClient.create(dataSource);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean("normalService")
        AdminService normalService(JdbcClient jdbcClient, ObjectMapper objectMapper) {
            return new AdminService(jdbcClient, objectMapper);
        }

        @Bean("failingService")
        AdminService failingService(JdbcClient jdbcClient) {
            ObjectMapper failingMapper = new ObjectMapper() {
                @Override
                public String writeValueAsString(Object value) throws JsonProcessingException {
                    throw new JsonProcessingException("serialization offline") { };
                }
            };
            return new AdminService(jdbcClient, failingMapper);
        }

        @Bean
        OutboxFleetHealthService outboxFleetHealthService() {
            return mock(OutboxFleetHealthService.class);
        }

        @Bean
        AdminOpsQueryService adminOpsQueryService(JdbcClient jdbcClient,
                                                   OutboxFleetHealthService outboxFleetHealthService) {
            return new AdminOpsQueryService(
                    new JdbcAdminOpsReadRepository(jdbcClient),
                    outboxFleetHealthService,
                    2_500);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
