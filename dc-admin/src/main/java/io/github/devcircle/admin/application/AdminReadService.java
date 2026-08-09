package io.github.devcircle.admin.application;

import io.github.devcircle.admin.api.AuditItem;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-side application service for the operations console.
 *
 * Keeping these queries out of {@link AdminService} makes the moderation
 * mutation boundary explicit and prevents read-only dashboard changes from
 * growing the transactional write service.
 */
@Service
public class AdminReadService {
    private final JdbcClient jdbcClient;

    public AdminReadService() {
        this.jdbcClient = null;
    }

    @Autowired
    public AdminReadService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
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
                    .query((rs, rowNum) -> new AuditItem(rs.getLong("id"), rs.getString("title"),
                            rs.getString("status"), "user-" + rs.getLong("author_id")))
                    .list();
        } catch (DataAccessException ex) {
            throw dependencyUnavailable(ex);
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
                    .param("size", boundedPageSize(size))
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
                        row.put("slaDeadline", rs.getTimestamp("sla_deadline") == null
                                ? null : rs.getTimestamp("sla_deadline").toInstant());
                        row.put("createdAt", rs.getTimestamp("created_at") == null
                                ? null : rs.getTimestamp("created_at").toInstant());
                        return row;
                    })
                    .list();
        } catch (DataAccessException ex) {
            throw dependencyUnavailable(ex);
        }
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
                    .param("size", boundedPageSize(size))
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
            throw dependencyUnavailable(ex);
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
                    .param("size", boundedPageSize(size))
                    .param("offset", offset)
                    .query((rs, rowNum) -> Map.<String, Object>of(
                            "userId", rs.getLong("user_id"),
                            "status", rs.getString("status"),
                            "riskScore", rs.getDouble("risk_score"),
                            "reason", rs.getString("reason"),
                            "restrictedUntil", rs.getTimestamp("restricted_until") == null
                                    ? "" : rs.getTimestamp("restricted_until").toInstant(),
                            "updatedAt", rs.getTimestamp("updated_at").toInstant()
                    ))
                    .list();
        } catch (DataAccessException ex) {
            throw dependencyUnavailable(ex);
        }
    }

    public Map<String, Object> riskProfile(Long userId) {
        requireDatabase();
        try {
            Map<String, Object> profile = jdbcClient.sql("""
                            SELECT user_id, status, risk_score, reason, updated_at
                            FROM user_risk_profile
                            WHERE user_id = :userId
                            """)
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
            return Map.of("posts", posts, "pendingAudits", pending, "auditPassRate", passRate,
                    "recommendImpressions", impressions, "recommendEvents", recEvents, "riskUsers", riskUsers);
        } catch (DataAccessException ex) {
            throw dependencyUnavailable(ex);
        }
    }

    public List<Map<String, Object>> themeMetrics() {
        requireDatabase();
        try {
            return jdbcClient.sql("""
                            SELECT theme, COUNT(*) AS posts, AVG(like_count) AS avg_likes,
                                   AVG(view_count) AS avg_views, SUM(solved_count) AS solves
                            FROM post
                            GROUP BY theme
                            """)
                    .query((rs, rowNum) -> Map.<String, Object>of(
                            "theme", rs.getString("theme"), "posts", rs.getLong("posts"),
                            "avgLikes", rs.getDouble("avg_likes"), "avgViews", rs.getDouble("avg_views"),
                            "solves", rs.getLong("solves")))
                    .list();
        } catch (DataAccessException ex) {
            throw dependencyUnavailable(ex);
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
                            "experimentId", rs.getString("experiment_id"), "name", rs.getString("name"),
                            "scene", rs.getString("scene"), "status", rs.getString("status"),
                            "trafficPercent", rs.getInt("traffic_percent"), "weights", rs.getString("weights"),
                            "updatedAt", rs.getTimestamp("updated_at").toInstant()))
                    .list();
        } catch (DataAccessException ex) {
            throw dependencyUnavailable(ex);
        }
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
                    .param("size", boundedPageSize(size))
                    .param("offset", offset)
                    .query((rs, rowNum) -> Map.<String, Object>of(
                            "id", rs.getLong("id"), "userId", rs.getLong("user_id"),
                            "action", rs.getString("action"), "targetType", rs.getString("target_type"),
                            "targetId", rs.getString("target_id"), "ip", rs.getString("ip") == null ? "" : rs.getString("ip"),
                            "traceId", rs.getString("trace_id") == null ? "" : rs.getString("trace_id"),
                            "createdAt", rs.getTimestamp("created_at").toInstant()))
                    .list();
        } catch (DataAccessException ex) {
            throw dependencyUnavailable(ex);
        }
    }

    private long boundedPageSize(int size) {
        return Math.max(1, Math.min(size, 100));
    }

    private long oneLong(String sql) {
        return jdbcClient.sql(sql).query(Long.class).single();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void requireDatabase() {
        if (jdbcClient == null) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "运营数据暂时不可用");
        }
    }

    private BusinessException dependencyUnavailable(DataAccessException cause) {
        BusinessException failure = new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "运营数据暂时不可用");
        failure.initCause(cause);
        return failure;
    }
}
