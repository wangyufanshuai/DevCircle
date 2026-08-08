package io.github.devcircle.content.infrastructure;

import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.content.api.CreatePostRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class ContentModerationStore {
    private final JdbcClient jdbcClient;
    private final ContentEventStore eventStore;

    public ContentModerationStore(JdbcTemplate jdbcTemplate, ContentEventStore eventStore) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.eventStore = eventStore;
    }

    public void createTask(Long postId, Long authorId, CreatePostRequest request) {
        ModerationRisk risk = moderationRisk(request);
        jdbcClient.sql("""
                        INSERT INTO moderation_task(post_id, author_id, queue_name, machine_result, risk_level, risk_score, reason, status, review_level, sla_deadline)
                        VALUES (:postId, :authorId, :queueName, :machineResult, :riskLevel, :riskScore, :reason,
                                'PENDING', :reviewLevel, TIMESTAMPADD(HOUR, :slaHours, CURRENT_TIMESTAMP))
                        """)
                .param("postId", postId)
                .param("authorId", authorId)
                .param("queueName", risk.queueName())
                .param("machineResult", risk.machineResult())
                .param("riskLevel", risk.riskLevel())
                .param("riskScore", risk.score())
                .param("reason", risk.reason())
                .param("reviewLevel", risk.reviewLevel())
                .param("slaHours", risk.slaHours())
                .update();
        if (risk.score() < 40) return;
        jdbcClient.sql("""
                        INSERT INTO content_risk_signal(user_id, post_id, signal_type, risk_score, description)
                        VALUES (:userId, :postId, :signalType, :riskScore, :description)
                        """)
                .param("userId", authorId)
                .param("postId", postId)
                .param("signalType", risk.signalType())
                .param("riskScore", risk.score())
                .param("description", risk.reason())
                .update();
        jdbcClient.sql("""
                        INSERT INTO user_risk_profile(user_id, status, risk_score, reason)
                        VALUES (:userId, :status, :riskScore, :reason)
                        AS incoming
                        ON DUPLICATE KEY UPDATE risk_score = GREATEST(risk_score, incoming.risk_score), status = incoming.status, reason = incoming.reason
                        """)
                .param("userId", authorId)
                .param("status", risk.score() >= 80 ? "RESTRICTED" : "WATCH")
                .param("riskScore", risk.score())
                .param("reason", risk.reason())
                .update();
        eventStore.append(EventTopics.RISK_SIGNAL_CREATED, "RISK_SIGNAL", postId.toString(),
                Map.of("postId", postId, "userId", authorId,
                        "riskScore", risk.score(), "riskLevel", risk.riskLevel()));
    }

    private ModerationRisk moderationRisk(CreatePostRequest request) {
        String title = request.title() == null ? "" : request.title();
        String content = request.content() == null ? "" : request.content();
        List<String> tags = request.tags() == null ? List.of() : request.tags();
        boolean sensitive = tags.stream().anyMatch(tag -> List.of("禁区", "血腥", "攻击", "外挂").contains(tag));
        boolean duplicateLike = title.length() > 4 && content.contains(title);
        boolean video = "VIDEO".equalsIgnoreCase(request.type());
        double score = 15 + (sensitive ? 60 : 0) + (duplicateLike ? 15 : 0)
                + (video ? 10 : 0) + (content.length() < 30 ? 20 : 0);
        String level = score >= 80 ? "HIGH" : score >= 45 ? "MEDIUM" : "LOW";
        String queue = "HIGH".equals(level) ? "senior-review"
                : "MEDIUM".equals(level) ? "manual-review" : "fast-lane";
        String reason = sensitive ? "命中敏感标签或高风险主题"
                : duplicateLike ? "标题与正文高度重复"
                : video ? "视频内容需要人工抽检" : "常规机审通过后等待人工抽样";
        String machineResult = score >= 80 ? "BLOCK_SUSPECTED"
                : score >= 45 ? "REVIEW_REQUIRED" : "PASS_LOW_RISK";
        String reviewLevel = "HIGH".equals(level) ? "ESCALATED" : "NORMAL";
        int sla = "HIGH".equals(level) ? 2 : "MEDIUM".equals(level) ? 8 : 24;
        String signalType = sensitive ? "SENSITIVE_TAG"
                : duplicateLike ? "DUPLICATE_CONTENT" : "CONTENT_RISK";
        return new ModerationRisk(queue, machineResult, level, score, reason,
                reviewLevel, sla, signalType);
    }

    private record ModerationRisk(String queueName, String machineResult, String riskLevel,
                                  double score, String reason, String reviewLevel,
                                  int slaHours, String signalType) {
    }
}
