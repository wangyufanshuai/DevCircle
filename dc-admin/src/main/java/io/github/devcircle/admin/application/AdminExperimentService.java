package io.github.devcircle.admin.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.admin.api.AdminActionResult;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Owns recommendation experiment validation and state transitions. */
@Service
public class AdminExperimentService {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final AdminMutationSupport support;

    @Autowired
    public AdminExperimentService(JdbcClient jdbcClient,
                                  ObjectMapper objectMapper,
                                  AdminMutationSupport support) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.support = support;
    }

    @Transactional
    public AdminActionResult create(Map<String, Object> payload,
                                    Long actorId,
                                    String traceId,
                                    String idempotencyKey) {
        support.requireDatabase();
        support.requireAuditor(actorId);
        Map<String, Object> safePayload = payload == null ? Map.of() : payload;
        String experimentId = text(safePayload.get("experimentId"),
                "exp-manual-" + support.requestFingerprint(
                        "experiment-id-v1", actorId, idempotencyKey).substring(0, 12),
                "推荐实验 ID", 64);
        if (!experimentId.matches("[A-Za-z0-9][A-Za-z0-9._-]{2,63}")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "推荐实验 ID 格式无效");
        }
        String name = text(safePayload.get("name"), "手动推荐实验", "推荐实验名称", 120);
        String scene = text(safePayload.get("scene"), "home", "推荐场景", 32)
                .toLowerCase(Locale.ROOT);
        if (!scene.matches("[a-z0-9][a-z0-9_-]{1,31}")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "推荐场景格式无效");
        }
        Object weights = safePayload.getOrDefault("weights", Map.of("theme", 1.0));
        String weightsJson = weightsJson(weights);
        String fingerprint = support.requestFingerprint(
                "admin-experiment-create-v1", experimentId, name, scene, weightsJson);
        return support.idempotent(actorId, "CREATE_EXPERIMENT", idempotencyKey, actorId,
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
                    support.logAudit(actorId, "CREATE_RECOMMEND_EXPERIMENT", "RECOMMEND_EXPERIMENT",
                            experimentId, null, traceId, Map.of("scene", scene, "status", "DRAFT"));
                    return new AdminActionResult("CREATE_RECOMMEND_EXPERIMENT",
                            Map.of("experimentId", experimentId, "scene", scene, "status", "DRAFT"));
                });
    }

    @Transactional
    public AdminActionResult updateStatus(String experimentId,
                                          String status,
                                          Long actorId,
                                          String traceId,
                                          String idempotencyKey) {
        support.requireDatabase();
        support.requireAuditor(actorId);
        String normalizedId = text(experimentId, null, "推荐实验 ID", 64);
        String normalizedStatus = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ACTIVE", "PAUSED").contains(normalizedStatus)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "推荐实验状态无效");
        }
        String fingerprint = support.requestFingerprint(
                "admin-experiment-status-v1", normalizedId, normalizedStatus);
        return support.idempotent(actorId, "EXPERIMENT_STATUS", idempotencyKey, actorId,
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
                        Long existing = jdbcClient.sql("""
                                        SELECT COUNT(*) FROM recommend_experiment
                                        WHERE experiment_id = :experimentId
                                        """)
                                .param("experimentId", normalizedId)
                                .query(Long.class)
                                .single();
                        if (existing == null || existing == 0) {
                            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "推荐实验不存在");
                        }
                        throw new BusinessException(ErrorCode.VERSION_CONFLICT, "推荐实验状态已变化，请刷新后重试");
                    }
                    support.logAudit(actorId, "UPDATE_RECOMMEND_EXPERIMENT", "RECOMMEND_EXPERIMENT",
                            normalizedId, null, traceId, Map.of("status", normalizedStatus));
                    return new AdminActionResult("UPDATE_RECOMMEND_EXPERIMENT",
                            Map.of("experimentId", normalizedId, "status", normalizedStatus));
                });
    }

    private String text(Object value, String fallback, String fieldName, int maxLength) {
        String result = value == null ? fallback : String.valueOf(value).trim();
        if (result == null || result.isBlank() || "null".equalsIgnoreCase(result)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, fieldName + "不能为空");
        }
        if (result.length() > maxLength) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, fieldName + "过长");
        }
        return result;
    }

    private String weightsJson(Object weights) {
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
}
