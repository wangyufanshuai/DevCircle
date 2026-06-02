package io.github.devcircle.content.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.seed.ThemeSeedPost;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.common.event.PostCreatedPayload;
import io.github.devcircle.common.model.MediaAssetView;
import io.github.devcircle.common.model.PostSummary;
import io.github.devcircle.content.api.CreatePostRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;

@Repository
public class PostRepository {
    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public PostRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.objectMapper = objectMapper;
    }

    public List<PostSummary> list(int page, int size) {
        return list(page, size, null, null, null);
    }

    public List<PostSummary> list(int page, int size, String theme, String contentKind, String difficulty) {
        int offset = Math.max(page - 1, 0) * size;
        return jdbcClient.sql("""
                        SELECT p.id, p.type, p.theme, p.content_kind, p.difficulty, p.solvable, p.solved_count,
                               p.title, LEFT(pc.markdown, 140) AS excerpt, p.author_id, p.status,
                               p.like_count, p.comment_count, p.view_count, p.created_at,
                               GROUP_CONCAT(t.name ORDER BY t.name SEPARATOR ',') AS tags
                        FROM post p
                        JOIN post_content pc ON pc.post_id = p.id
                        LEFT JOIN post_tag pt ON pt.post_id = p.id
                        LEFT JOIN tag t ON t.id = pt.tag_id
                        WHERE (:theme IS NULL OR p.theme = :theme)
                          AND (:contentKind IS NULL OR p.content_kind = :contentKind)
                          AND (:difficulty IS NULL OR p.difficulty = :difficulty)
                        GROUP BY p.id, p.type, p.theme, p.content_kind, p.difficulty, p.solvable, p.solved_count,
                                 p.title, pc.markdown, p.author_id, p.status, p.like_count, p.comment_count, p.view_count, p.created_at
                        ORDER BY p.created_at DESC
                        LIMIT :size OFFSET :offset
                        """)
                .param("size", size)
                .param("offset", offset)
                .param("theme", normalizeNullableTheme(theme))
                .param("contentKind", normalizeNullableContentKind(contentKind))
                .param("difficulty", normalizeNullableDifficulty(difficulty))
                .query((rs, rowNum) -> new PostSummary(
                        rs.getLong("id"),
                        rs.getString("type"),
                        rs.getString("theme"),
                        rs.getString("content_kind"),
                        rs.getString("difficulty"),
                        rs.getBoolean("solvable"),
                        rs.getLong("solved_count"),
                        rs.getString("title"),
                        rs.getString("excerpt"),
                        "user-" + rs.getLong("author_id"),
                        splitTags(rs.getString("tags")),
                        mediaAssets(rs.getLong("id")),
                        rs.getLong("like_count"),
                        rs.getLong("comment_count"),
                        rs.getLong("view_count"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant()
                ))
                .list();
    }

    public long count() {
        Long count = jdbcClient.sql("SELECT COUNT(*) FROM post").query(Long.class).single();
        return count == null ? 0 : count;
    }

    public PostSummary detail(Long id) {
        return jdbcClient.sql("""
                        SELECT p.id, p.type, p.theme, p.content_kind, p.difficulty, p.solvable, p.solved_count,
                               p.title, LEFT(pc.markdown, 140) AS excerpt, p.author_id, p.status,
                               p.like_count, p.comment_count, p.view_count, p.created_at,
                               pc.clues, pc.suspects, pc.timeline, pc.question, pc.answer_keywords,
                               pc.explanation, pc.worldbuilding, pc.cosmic_facts, pc.related_post_ids,
                               GROUP_CONCAT(t.name ORDER BY t.name SEPARATOR ',') AS tags
                        FROM post p
                        JOIN post_content pc ON pc.post_id = p.id
                        LEFT JOIN post_tag pt ON pt.post_id = p.id
                        LEFT JOIN tag t ON t.id = pt.tag_id
                        WHERE p.id = :id
                        GROUP BY p.id, p.type, p.theme, p.content_kind, p.difficulty, p.solvable, p.solved_count,
                                 p.title, pc.markdown, p.author_id, p.status, p.like_count, p.comment_count, p.view_count, p.created_at,
                                 pc.clues, pc.suspects, pc.timeline, pc.question, pc.answer_keywords,
                                 pc.explanation, pc.worldbuilding, pc.cosmic_facts, pc.related_post_ids
                        """)
                .param("id", id)
                .query((rs, rowNum) -> new PostSummary(
                        rs.getLong("id"),
                        rs.getString("type"),
                        rs.getString("theme"),
                        rs.getString("content_kind"),
                        rs.getString("difficulty"),
                        rs.getBoolean("solvable"),
                        rs.getLong("solved_count"),
                        rs.getString("title"),
                        rs.getString("excerpt"),
                        "user-" + rs.getLong("author_id"),
                        splitTags(rs.getString("tags")),
                        mediaAssets(rs.getLong("id")),
                        rs.getLong("like_count"),
                        rs.getLong("comment_count"),
                        rs.getLong("view_count"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant(),
                        readStringList(rs.getString("clues")),
                        readStringList(rs.getString("suspects")),
                        readStringList(rs.getString("timeline")),
                        Optional.ofNullable(rs.getString("question")).orElse(""),
                        readStringList(rs.getString("answer_keywords")),
                        Optional.ofNullable(rs.getString("explanation")).orElse(""),
                        readStringList(rs.getString("worldbuilding")),
                        readStringList(rs.getString("cosmic_facts")),
                        readLongList(rs.getString("related_post_ids"))
                ))
                .single();
    }

    public List<PostSummary> related(Long id, int size) {
        PostSummary current = detail(id);
        return jdbcClient.sql("""
                        SELECT p.id, p.type, p.theme, p.content_kind, p.difficulty, p.solvable, p.solved_count,
                               p.title, LEFT(pc.markdown, 140) AS excerpt, p.author_id, p.status,
                               p.like_count, p.comment_count, p.view_count, p.created_at,
                               GROUP_CONCAT(t.name ORDER BY t.name SEPARATOR ',') AS tags
                        FROM post p
                        JOIN post_content pc ON pc.post_id = p.id
                        LEFT JOIN post_tag pt ON pt.post_id = p.id
                        LEFT JOIN tag t ON t.id = pt.tag_id
                        WHERE p.id <> :id AND p.theme = :theme AND p.status = 'PUBLISHED'
                        GROUP BY p.id, p.type, p.theme, p.content_kind, p.difficulty, p.solvable, p.solved_count,
                                 p.title, pc.markdown, p.author_id, p.status, p.like_count, p.comment_count, p.view_count, p.created_at
                        ORDER BY p.like_count DESC, p.created_at DESC
                        LIMIT :size
                        """)
                .param("id", id)
                .param("theme", current.theme())
                .param("size", Math.max(1, Math.min(size, 20)))
                .query((rs, rowNum) -> new PostSummary(
                        rs.getLong("id"),
                        rs.getString("type"),
                        rs.getString("theme"),
                        rs.getString("content_kind"),
                        rs.getString("difficulty"),
                        rs.getBoolean("solvable"),
                        rs.getLong("solved_count"),
                        rs.getString("title"),
                        rs.getString("excerpt"),
                        "user-" + rs.getLong("author_id"),
                        splitTags(rs.getString("tags")),
                        mediaAssets(rs.getLong("id")),
                        rs.getLong("like_count"),
                        rs.getLong("comment_count"),
                        rs.getLong("view_count"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant()
                ))
                .list();
    }

    @Transactional
    public PostSummary create(Long authorId, CreatePostRequest request) {
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO post(author_id, title, type, theme, content_kind, difficulty, solvable, status) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING')",
                    Statement.RETURN_GENERATED_KEYS
            );
            statement.setLong(1, authorId);
            statement.setString(2, request.title());
            statement.setString(3, normalizeType(request.type()));
            statement.setString(4, normalizeTheme(request.theme()));
            statement.setString(5, normalizeContentKind(request.contentKind()));
            statement.setString(6, normalizeDifficulty(request.difficulty()));
            statement.setBoolean(7, Boolean.TRUE.equals(request.solvable()));
            return statement;
        }, keyHolder);
        Number generatedKey = keyHolder.getKey();
        if (generatedKey == null) {
            throw new DataAccessException("Failed to create post") {
            };
        }
        Long postId = generatedKey.longValue();
        jdbcClient.sql("""
                        INSERT INTO post_content(post_id, markdown, clues, question, answer_keywords, solution, explanation)
                        VALUES (:postId, :markdown, CAST(:clues AS JSON), :question, CAST(:answerKeywords AS JSON), :solution, :explanation)
                        """)
                .param("postId", postId)
                .param("markdown", request.content())
                .param("clues", cluesJson(request.clues()))
                .param("question", Boolean.TRUE.equals(request.solvable()) ? "请根据线索提交你的推理结论。" : "")
                .param("answerKeywords", cluesJson(request.solution()))
                .param("solution", request.solution() == null ? "" : request.solution())
                .param("explanation", request.solution() == null ? "" : request.solution())
                .update();
        for (String tagName : safeTags(request.tags())) {
            Long tagId = upsertTag(tagName);
            jdbcClient.sql("INSERT IGNORE INTO post_tag(post_id, tag_id) VALUES (:postId, :tagId)")
                    .param("postId", postId)
                    .param("tagId", tagId)
                    .update();
        }
        int index = 0;
        for (Long mediaId : safeMediaIds(request.mediaIds())) {
            jdbcClient.sql("INSERT IGNORE INTO post_media(post_id, media_id, sort_order) VALUES (:postId, :mediaId, :sortOrder)")
                    .param("postId", postId)
                    .param("mediaId", mediaId)
                    .param("sortOrder", index++)
                    .update();
        }
        createModerationTask(postId, authorId, request);
        writeOutbox(postId, authorId, request);
        return new PostSummary(postId, normalizeType(request.type()), normalizeTheme(request.theme()), normalizeContentKind(request.contentKind()),
                normalizeDifficulty(request.difficulty()), Boolean.TRUE.equals(request.solvable()), 0,
                request.title(), excerpt(request.content()), "user-" + authorId, safeTags(request.tags()), mediaAssets(postId), 0, 0, 0, "PENDING", Instant.now());
    }

    @Transactional
    public int importSeedsIfEmpty(List<ThemeSeedPost> seeds) {
        int imported = 0;
        for (ThemeSeedPost seed : seeds) {
            Long existing;
            try {
                existing = jdbcClient.sql("SELECT COUNT(*) FROM post WHERE id = :id")
                        .param("id", seed.id())
                        .query(Long.class)
                        .single();
            } catch (DataAccessException ex) {
                return 0;
            }
            if (existing != null && existing > 0) {
                continue;
            }
            jdbcClient.sql("""
                            INSERT INTO post(id, author_id, type, theme, content_kind, difficulty, solvable, solved_count,
                                             title, status, like_count, comment_count, view_count, created_at)
                            VALUES (:id, :authorId, :type, :theme, :contentKind, :difficulty, :solvable, :solvedCount,
                                    :title, 'PUBLISHED', :likes, :comments, :views, :createdAt)
                            """)
                    .param("id", seed.id())
                    .param("authorId", Math.abs(seed.author().hashCode() % 10000) + 1)
                    .param("type", seed.type())
                    .param("theme", seed.theme())
                    .param("contentKind", seed.contentKind())
                    .param("difficulty", seed.difficulty())
                    .param("solvable", seed.solvable())
                    .param("solvedCount", seed.solvedCount())
                    .param("title", seed.title())
                    .param("likes", seed.likes())
                    .param("comments", seed.comments())
                    .param("views", seed.views())
                    .param("createdAt", java.sql.Timestamp.from(seed.createdAt()))
                    .update();
            jdbcClient.sql("""
                            INSERT INTO post_content(post_id, markdown, clues, solution, suspects, timeline, question,
                                                     answer_keywords, explanation, worldbuilding, cosmic_facts, related_post_ids)
                            VALUES (:postId, :markdown, CAST(:clues AS JSON), :solution, CAST(:suspects AS JSON),
                                    CAST(:timeline AS JSON), :question, CAST(:answerKeywords AS JSON), :explanation,
                                    CAST(:worldbuilding AS JSON), CAST(:cosmicFacts AS JSON), CAST(:relatedPostIds AS JSON))
                            """)
                    .param("postId", seed.id())
                    .param("markdown", seed.excerpt())
                    .param("clues", json(seed.clues()))
                    .param("solution", seed.answerKeywords().isEmpty() ? "" : String.join(",", seed.answerKeywords()))
                    .param("suspects", json(seed.suspects()))
                    .param("timeline", json(seed.timeline()))
                    .param("question", seed.question())
                    .param("answerKeywords", json(seed.answerKeywords()))
                    .param("explanation", seed.explanation())
                    .param("worldbuilding", json(seed.worldbuilding()))
                    .param("cosmicFacts", json(seed.cosmicFacts()))
                    .param("relatedPostIds", json(seed.relatedPostIds()))
                    .update();
            for (String tagName : seed.tags()) {
                Long tagId = upsertTag(tagName);
                jdbcClient.sql("INSERT IGNORE INTO post_tag(post_id, tag_id) VALUES (:postId, :tagId)")
                        .param("postId", seed.id())
                        .param("tagId", tagId)
                        .update();
            }
            imported++;
        }
        return imported;
    }

    @Transactional
    public Map<String, Object> createAppeal(Long userId, Long postId, String reason) {
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO appeal_record(post_id, user_id, reason) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            statement.setLong(1, postId);
            statement.setLong(2, userId);
            statement.setString(3, reason == null || reason.isBlank() ? "用户申请复审" : reason);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        Long appealId = key == null ? 0L : key.longValue();
        writeOutbox(EventTopics.APPEAL_CREATED, "APPEAL", appealId.toString(), Map.of("appealId", appealId, "postId", postId, "userId", userId));
        return Map.of("appealId", appealId, "postId", postId, "status", "PENDING");
    }

    private Long upsertTag(String name) {
        jdbcClient.sql("INSERT INTO tag(name, use_count) VALUES (:name, 1) ON DUPLICATE KEY UPDATE use_count = use_count + 1")
                .param("name", name)
                .update();
        return jdbcClient.sql("SELECT id FROM tag WHERE name = :name")
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private void writeOutbox(Long postId, Long authorId, CreatePostRequest request) {
        try {
            String payload = objectMapper.writeValueAsString(new PostCreatedPayload(postId, authorId, request.title(), request.tags(), normalizeTheme(request.theme()), normalizeContentKind(request.contentKind())));
            jdbcClient.sql("""
                            INSERT INTO outbox_event(event_id, topic, aggregate_type, aggregate_id, payload)
                            VALUES (:eventId, :topic, 'POST', :aggregateId, CAST(:payload AS JSON))
                            """)
                    .param("eventId", UUID.randomUUID().toString())
                    .param("topic", EventTopics.POST_CREATED)
                    .param("aggregateId", postId.toString())
                    .param("payload", payload)
                    .update();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize post.created payload", ex);
        }
    }

    private List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return Arrays.asList(tags.split(","));
    }

    private List<MediaAssetView> mediaAssets(Long postId) {
        return jdbcClient.sql("""
                        SELECT m.id, m.object_key, m.url, m.content_type, m.size_bytes, m.width, m.height, m.duration_seconds, m.status
                        FROM post_media pm
                        JOIN media_asset m ON m.id = pm.media_id
                        WHERE pm.post_id = :postId
                        ORDER BY pm.sort_order, m.id
                        """)
                .param("postId", postId)
                .query((rs, rowNum) -> new MediaAssetView(
                        rs.getLong("id"),
                        rs.getString("object_key"),
                        rs.getString("url"),
                        rs.getString("content_type"),
                        rs.getLong("size_bytes"),
                        (Integer) rs.getObject("width"),
                        (Integer) rs.getObject("height"),
                        (Integer) rs.getObject("duration_seconds"),
                        rs.getString("status")
                ))
                .list();
    }

    private String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return "TEXT";
        }
        String normalized = type.toUpperCase();
        if (!List.of("TEXT", "IMAGE", "VIDEO").contains(normalized)) {
            return "TEXT";
        }
        return normalized;
    }

    private String normalizeTheme(String theme) {
        String normalized = theme == null || theme.isBlank() ? "DEDUCTION" : theme.toUpperCase();
        if (!List.of("DEDUCTION", "SCI_FI", "COSMOS").contains(normalized)) {
            return "DEDUCTION";
        }
        return normalized;
    }

    private String normalizeContentKind(String contentKind) {
        String normalized = contentKind == null || contentKind.isBlank() ? "CASE_FILE" : contentKind.toUpperCase();
        if (!List.of("CASE_FILE", "PUZZLE", "FICTION", "WORLDBUILDING", "COSMIC_ARTICLE", "OBSERVATION_LOG").contains(normalized)) {
            return "CASE_FILE";
        }
        return normalized;
    }

    private String normalizeDifficulty(String difficulty) {
        String normalized = difficulty == null || difficulty.isBlank() ? "MEDIUM" : difficulty.toUpperCase();
        if (!List.of("EASY", "MEDIUM", "HARD").contains(normalized)) {
            return "MEDIUM";
        }
        return normalized;
    }

    private String normalizeNullableTheme(String theme) {
        return theme == null || theme.isBlank() ? null : normalizeTheme(theme);
    }

    private String normalizeNullableContentKind(String contentKind) {
        return contentKind == null || contentKind.isBlank() ? null : normalizeContentKind(contentKind);
    }

    private String normalizeNullableDifficulty(String difficulty) {
        return difficulty == null || difficulty.isBlank() ? null : normalizeDifficulty(difficulty);
    }

    private String cluesJson(String clues) {
        try {
            if (clues == null || clues.isBlank()) {
                return "[]";
            }
            List<String> values = Arrays.stream(clues.split("\\n|;|；"))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .toList();
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }

    private void writeOutbox(String topic, String aggregateType, String aggregateId, Object payload) {
        try {
            jdbcClient.sql("""
                            INSERT INTO outbox_event(event_id, topic, aggregate_type, aggregate_id, payload)
                            VALUES (:eventId, :topic, :aggregateType, :aggregateId, CAST(:payload AS JSON))
                            """)
                    .param("eventId", UUID.randomUUID().toString())
                    .param("topic", topic)
                    .param("aggregateType", aggregateType)
                    .param("aggregateId", aggregateId)
                    .param("payload", objectMapper.writeValueAsString(payload))
                    .update();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize outbox payload", ex);
        }
    }

    private void createModerationTask(Long postId, Long authorId, CreatePostRequest request) {
        ModerationRisk risk = moderationRisk(request);
        jdbcClient.sql("""
                        INSERT INTO moderation_task(post_id, author_id, queue_name, machine_result, risk_level, risk_score, reason, status, review_level, sla_deadline)
                        VALUES (:postId, :authorId, :queueName, :machineResult, :riskLevel, :riskScore, :reason, 'PENDING', :reviewLevel, DATE_ADD(CURRENT_TIMESTAMP, INTERVAL :slaHours HOUR))
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
        if (risk.score() >= 40) {
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
                            ON DUPLICATE KEY UPDATE risk_score = GREATEST(risk_score, VALUES(risk_score)), status = VALUES(status), reason = VALUES(reason)
                            """)
                    .param("userId", authorId)
                    .param("status", risk.score() >= 80 ? "RESTRICTED" : "WATCH")
                    .param("riskScore", risk.score())
                    .param("reason", risk.reason())
                    .update();
            writeOutbox(EventTopics.RISK_SIGNAL_CREATED, "RISK_SIGNAL", postId.toString(), Map.of("postId", postId, "userId", authorId, "riskScore", risk.score(), "riskLevel", risk.riskLevel()));
        }
    }

    private ModerationRisk moderationRisk(CreatePostRequest request) {
        String title = request.title() == null ? "" : request.title();
        String content = request.content() == null ? "" : request.content();
        List<String> tags = safeTags(request.tags());
        boolean sensitive = tags.stream().anyMatch(tag -> List.of("禁区", "血腥", "攻击", "外挂").contains(tag));
        boolean duplicateLike = title.length() > 4 && content.contains(title);
        boolean video = "VIDEO".equalsIgnoreCase(request.type());
        double score = 15 + (sensitive ? 60 : 0) + (duplicateLike ? 15 : 0) + (video ? 10 : 0) + (content.length() < 30 ? 20 : 0);
        String level = score >= 80 ? "HIGH" : score >= 45 ? "MEDIUM" : "LOW";
        String queue = "HIGH".equals(level) ? "senior-review" : "MEDIUM".equals(level) ? "manual-review" : "fast-lane";
        String reason = sensitive ? "命中敏感标签或高风险主题" : duplicateLike ? "标题与正文高度重复" : video ? "视频内容需要人工抽检" : "常规机审通过后等待人工抽样";
        String machineResult = score >= 80 ? "BLOCK_SUSPECTED" : score >= 45 ? "REVIEW_REQUIRED" : "PASS_LOW_RISK";
        String reviewLevel = "HIGH".equals(level) ? "ESCALATED" : "NORMAL";
        int sla = "HIGH".equals(level) ? 2 : "MEDIUM".equals(level) ? 8 : 24;
        String signalType = sensitive ? "SENSITIVE_TAG" : duplicateLike ? "DUPLICATE_CONTENT" : "CONTENT_RISK";
        return new ModerationRisk(queue, machineResult, level, score, reason, reviewLevel, sla, signalType);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }

    private List<String> readStringList(String json) {
        try {
            if (json == null || json.isBlank()) {
                return List.of();
            }
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception ex) {
            return List.of();
        }
    }

    private List<Long> readLongList(String json) {
        try {
            if (json == null || json.isBlank()) {
                return List.of();
            }
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, Long.class));
        } catch (Exception ex) {
            return List.of();
        }
    }

    private List<String> safeTags(List<String> tags) {
        return tags == null ? List.of() : tags;
    }

    private List<Long> safeMediaIds(List<Long> mediaIds) {
        return mediaIds == null ? List.of() : mediaIds;
    }

    private String excerpt(String content) {
        return content.substring(0, Math.min(140, content.length()));
    }

    private record ModerationRisk(String queueName, String machineResult, String riskLevel, double score, String reason, String reviewLevel, int slaHours, String signalType) {
    }
}
