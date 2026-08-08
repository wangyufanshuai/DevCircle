package io.github.devcircle.admin.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.admin.api.AdminActionResult;
import io.github.devcircle.admin.api.DltReplayRequest;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.common.event.EventEnvelopeValidator;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.common.event.ValidatedEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class DltReplayService {
    private static final Map<String, Set<String>> REPLAY_CONTRACTS = Map.ofEntries(
            Map.entry(EventTopics.POST_CREATED, Set.of("dc-content")),
            Map.entry(EventTopics.POST_UPDATED, Set.of("dc-content")),
            Map.entry(EventTopics.POST_DELETED, Set.of("dc-content")),
            Map.entry(EventTopics.AUDIT_COMPLETED, Set.of("dc-admin")),
            Map.entry(EventTopics.CONTENT_REJECTED, Set.of("dc-admin")),
            Map.entry(EventTopics.APPEAL_RESOLVED, Set.of("dc-admin")),
            Map.entry(EventTopics.USER_REGISTERED, Set.of("dc-auth")),
            Map.entry(EventTopics.FOLLOW_CHANGED, Set.of("dc-user")),
            Map.entry(EventTopics.INTERACTION_CHANGED, Set.of("dc-interaction")),
            Map.entry(EventTopics.COMMENT_CREATED, Set.of("dc-comment")),
            Map.entry(EventTopics.COMMENT_CHANGED, Set.of("dc-comment")));

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final EventEnvelopeValidator envelopeValidator;
    private final DltRecordReader recordReader;
    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final TransactionTemplate transaction;
    private final TransactionTemplate failureTransaction;
    private final long publishTimeoutMs;

    public DltReplayService(JdbcClient jdbcClient, ObjectMapper objectMapper, DltRecordReader recordReader,
                            KafkaTemplate<Object, Object> kafkaTemplate,
                            PlatformTransactionManager transactionManager,
                            @Value("${devcircle.kafka.dlt.replay-publish-timeout-ms:5000}") long publishTimeoutMs) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.envelopeValidator = new EventEnvelopeValidator(objectMapper);
        this.recordReader = recordReader;
        this.kafkaTemplate = kafkaTemplate;
        this.transaction = new TransactionTemplate(transactionManager);
        this.failureTransaction = new TransactionTemplate(transactionManager);
        this.failureTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.publishTimeoutMs = Math.max(100, publishTimeoutMs);
    }

    public AdminActionResult replay(DltReplayRequest request, Long actorId, String traceId, String idempotencyKey) {
        ReplayCommand command = validate(request, actorId, idempotencyKey);
        DltRecordReader.DltRecord record = recordReader.read(
                command.sourceTopic(), command.partition(), command.offset());
        ValidatedEvent event = validateRecord(command, record);
        String fingerprint = fingerprint(command);
        try {
            AdminActionResult result = transaction.execute(status ->
                    publishWithinTransaction(command, record, event, actorId, traceId, idempotencyKey, fingerprint));
            if (result == null) throw new IllegalStateException("DLT replay transaction returned no result");
            return result;
        } catch (DltPublishException ex) {
            recordFailure(command, event, actorId, traceId, ex.getCause());
            BusinessException failure = new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                    "死信重放暂时失败，请稍后使用相同参数重试");
            failure.initCause(ex.getCause());
            throw failure;
        } catch (DataAccessException ex) {
            BusinessException failure = new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                    "死信重放审计暂时不可用，请稍后重试");
            failure.initCause(ex);
            throw failure;
        }
    }

    private AdminActionResult publishWithinTransaction(
            ReplayCommand command, DltRecordReader.DltRecord record, ValidatedEvent event,
            Long actorId, String traceId, String idempotencyKey, String fingerprint) {
        int inserted = jdbcClient.sql("""
                        INSERT IGNORE INTO kafka_dlt_replay(
                            idempotency_key, request_fingerprint, source_topic, dlt_topic,
                            dlt_partition, dlt_offset, event_id, actor_id, reason)
                        VALUES (:key, :fingerprint, :sourceTopic, :dltTopic,
                            :partition, :offset, :eventId, :actorId, :reason)
                        """)
                .param("key", idempotencyKey)
                .param("fingerprint", fingerprint)
                .param("sourceTopic", command.sourceTopic())
                .param("dltTopic", record.dltTopic())
                .param("partition", command.partition())
                .param("offset", command.offset())
                .param("eventId", event.eventId().toString())
                .param("actorId", actorId)
                .param("reason", command.reason())
                .update();
        if (inserted == 0) {
            ReplayRow existing = jdbcClient.sql("""
                            SELECT request_fingerprint, source_topic, dlt_partition, dlt_offset, event_id
                            FROM kafka_dlt_replay WHERE idempotency_key = :key
                            """)
                    .param("key", idempotencyKey)
                    .query((rs, rowNum) -> new ReplayRow(
                            rs.getString("request_fingerprint"), rs.getString("source_topic"),
                            rs.getInt("dlt_partition"), rs.getLong("dlt_offset"), rs.getString("event_id")))
                    .optional()
                    .orElseThrow(() -> new BusinessException(ErrorCode.DUPLICATE_OPERATION,
                            "该死信记录已由其他重放请求处理"));
            if (!existing.fingerprint().equals(fingerprint)) {
                throw new BusinessException(ErrorCode.DUPLICATE_OPERATION,
                        "Idempotency-Key 已被其他死信重放参数使用");
            }
            return new AdminActionResult("REPLAY_KAFKA_DLT", replayAttributes(
                    existing.sourceTopic(), existing.partition(), existing.offset(), existing.eventId(), true));
        }

        try {
            kafkaTemplate.send(command.sourceTopic(), record.key(), record.value())
                    .get(publishTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new DltPublishException(ex);
        } catch (Exception ex) {
            throw new DltPublishException(ex);
        }
        insertAudit(actorId, "REPLAY_KAFKA_DLT", record.dltTopic(),
                command.partition() + ":" + command.offset(), traceId,
                Map.of("sourceTopic", command.sourceTopic(), "eventId", event.eventId().toString(),
                        "reason", command.reason(), "status", "PUBLISHED"));
        return new AdminActionResult("REPLAY_KAFKA_DLT", replayAttributes(
                command.sourceTopic(), command.partition(), command.offset(), event.eventId().toString(), false));
    }

    private void recordFailure(ReplayCommand command, ValidatedEvent event, Long actorId,
                               String traceId, Throwable failure) {
        try {
            failureTransaction.executeWithoutResult(status -> insertAudit(actorId, "REPLAY_KAFKA_DLT_FAILED",
                    command.sourceTopic() + ".DLT", command.partition() + ":" + command.offset(), traceId,
                    Map.of("sourceTopic", command.sourceTopic(), "eventId", event.eventId().toString(),
                            "reason", command.reason(), "status", "FAILED",
                            "failure", safeFailure(failure))));
        } catch (RuntimeException auditFailure) {
            if (failure != null) failure.addSuppressed(auditFailure);
        }
    }

    private void insertAudit(Long actorId, String action, String targetType, String targetId,
                             String traceId, Map<String, Object> metadata) {
        try {
            jdbcClient.sql("""
                            INSERT INTO audit_log(user_id, action, target_type, target_id, trace_id, metadata)
                            VALUES (:actorId, :action, :targetType, :targetId, :traceId, CAST(:metadata AS JSON))
                            """)
                    .param("actorId", actorId)
                    .param("action", action)
                    .param("targetType", targetType)
                    .param("targetId", targetId)
                    .param("traceId", traceId)
                    .param("metadata", objectMapper.writeValueAsString(metadata))
                    .update();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize DLT replay audit metadata", ex);
        }
    }

    private ReplayCommand validate(DltReplayRequest request, Long actorId, String idempotencyKey) {
        if (request == null || request.sourceTopic() == null || request.reason() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "死信重放参数不完整");
        }
        String sourceTopic = request.sourceTopic();
        if (!sourceTopic.equals(sourceTopic.trim()) || !REPLAY_CONTRACTS.containsKey(sourceTopic)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不允许重放该事件主题");
        }
        String reason = request.reason().trim();
        if (reason.length() < 4 || reason.length() > 512 || request.partition() < 0 || request.offset() < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "死信位置或重放理由无效");
        }
        if (actorId == null || actorId <= 0) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅管理员可以重放死信事件");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "死信重放必须提供有效的 Idempotency-Key");
        }
        return new ReplayCommand(sourceTopic, request.partition(), request.offset(), reason);
    }

    private ValidatedEvent validateRecord(ReplayCommand command, DltRecordReader.DltRecord record) {
        if (!command.sourceTopic().equals(record.originalTopic())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "死信记录的原始主题与请求不一致");
        }
        try {
            return envelopeValidator.parse(record.value(),
                    Map.of(command.sourceTopic(), REPLAY_CONTRACTS.get(command.sourceTopic())));
        } catch (IllegalArgumentException ex) {
            BusinessException failure = new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "死信记录不是可重放的版本化领域事件");
            failure.initCause(ex);
            throw failure;
        }
    }

    private String fingerprint(ReplayCommand command) {
        String canonical = command.sourceTopic() + '\u001f' + command.partition() + '\u001f'
                + command.offset() + '\u001f' + command.reason();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private Map<String, Object> replayAttributes(String sourceTopic, int partition, long offset,
                                                  String eventId, boolean duplicate) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("sourceTopic", sourceTopic);
        attributes.put("dltTopic", sourceTopic + ".DLT");
        attributes.put("partition", partition);
        attributes.put("offset", offset);
        attributes.put("eventId", eventId);
        attributes.put("duplicateRequest", duplicate);
        return Map.copyOf(attributes);
    }

    private String safeFailure(Throwable failure) {
        if (failure == null) return "unknown Kafka replay failure";
        String value = failure.getClass().getSimpleName() + ": "
                + (failure.getMessage() == null ? "no message" : failure.getMessage());
        return value.substring(0, Math.min(value.length(), 500));
    }

    private record ReplayCommand(String sourceTopic, int partition, long offset, String reason) {
    }

    private record ReplayRow(String fingerprint, String sourceTopic, int partition, long offset, String eventId) {
    }

    private static final class DltPublishException extends RuntimeException {
        DltPublishException(Throwable cause) {
            super(cause);
        }
    }
}
