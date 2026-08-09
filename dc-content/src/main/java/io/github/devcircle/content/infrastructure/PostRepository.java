package io.github.devcircle.content.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.seed.ThemeContentLibrary;
import io.github.devcircle.seed.ThemeSeedPost;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.content.contract.MediaAssetView;
import io.github.devcircle.content.contract.PostSummary;
import io.github.devcircle.content.api.CreatePostRequest;
import io.github.devcircle.content.api.DraftView;
import io.github.devcircle.content.api.SaveDraftRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.github.devcircle.content.domain.PostMetadataPolicy.normalizeContentKind;
import static io.github.devcircle.content.domain.PostMetadataPolicy.normalizeDifficulty;
import static io.github.devcircle.content.domain.PostMetadataPolicy.normalizeTags;
import static io.github.devcircle.content.domain.PostMetadataPolicy.normalizeTheme;
import static io.github.devcircle.content.domain.PostMetadataPolicy.normalizeType;

@Repository
public class PostRepository {
    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final PostQueryRepository postQueryRepository;
    private final DraftRepository draftRepository;
    private final DraftMediaRepository draftMediaRepository;
    private final PostTagStore postTagStore;
    private final ContentIdempotencyStore idempotencyStore;
    private final ContentEventStore eventStore;
    private final ContentModerationStore moderationStore;

    public PostRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                          PostQueryRepository postQueryRepository,
                          DraftRepository draftRepository,
                          DraftMediaRepository draftMediaRepository,
                          PostTagStore postTagStore,
                          ContentIdempotencyStore idempotencyStore,
                          ContentEventStore eventStore,
                          ContentModerationStore moderationStore) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.objectMapper = objectMapper;
        this.postQueryRepository = postQueryRepository;
        this.draftRepository = draftRepository;
        this.draftMediaRepository = draftMediaRepository;
        this.postTagStore = postTagStore;
        this.idempotencyStore = idempotencyStore;
        this.eventStore = eventStore;
        this.moderationStore = moderationStore;
    }

    public PostSummary create(Long authorId, CreatePostRequest request) {
        return create(authorId, request, "internal-" + UUID.randomUUID());
    }

    @Transactional
    public PostSummary create(Long authorId, CreatePostRequest request, String idempotencyKey) {
        Long existingId = idempotencyStore.claim(authorId, "CREATE_POST", idempotencyKey,
                IdempotencyFingerprint.of("create-post-v1", request.title(), request.content(), request.tags(), request.type(),
                        request.mediaIds(), request.theme(), request.contentKind(), request.difficulty(),
                        request.solvable(), request.clues(), request.solution()));
        if (existingId != null) {
            return postQueryRepository.detail(existingId);
        }
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO post(author_id, title, type, theme, content_kind, difficulty, solvable, status) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING')",
                    new String[]{"id"}
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
        postTagStore.replace(postId, request.tags());
        int index = 0;
        for (Long mediaId : safeMediaIds(request.mediaIds())) {
            draftMediaRepository.requireOwnedCompleted(authorId, mediaId);
            jdbcClient.sql("INSERT IGNORE INTO post_media(post_id, media_id, sort_order) VALUES (:postId, :mediaId, :sortOrder)")
                    .param("postId", postId)
                    .param("mediaId", mediaId)
                    .param("sortOrder", index++)
                    .update();
        }
        moderationStore.createTask(postId, authorId, request);
        writeOutbox(postId, authorId, request);
        idempotencyStore.attachResource(idempotencyKey, postId);
        return new PostSummary(postId, normalizeType(request.type()), normalizeTheme(request.theme()), normalizeContentKind(request.contentKind()),
                normalizeDifficulty(request.difficulty()), Boolean.TRUE.equals(request.solvable()), 0,
                request.title(), excerpt(request.content()), "user-" + authorId, authorId,
                normalizeTags(request.tags()), mediaAssets(postId), 0, 0, 0, "PENDING", Instant.now());
    }

    @Transactional
    public DraftView createDraft(Long authorId, SaveDraftRequest request, String idempotencyKey) {
        return draftRepository.create(authorId, request, idempotencyKey);
    }

    public List<DraftView> listDrafts(Long authorId, int size) {
        return draftRepository.list(authorId, size);
    }

    public DraftView getDraft(Long authorId, Long draftId) {
        return draftRepository.get(authorId, draftId);
    }

    @Transactional
    public DraftView updateDraft(Long authorId, Long draftId, SaveDraftRequest request, String idempotencyKey) {
        return draftRepository.update(authorId, draftId, request, idempotencyKey);
    }

    @Transactional
    public DraftView submitDraft(Long authorId, Long draftId, long version, String idempotencyKey) {
        return draftRepository.submit(authorId, draftId, version, idempotencyKey);
    }

    @Transactional
    public int importSeedsIfEmpty(List<ThemeSeedPost> seeds) {
        int imported = 0;
        for (ThemeSeedPost seed : seeds) {
            Long existing = jdbcClient.sql("SELECT COUNT(*) FROM post WHERE id = :id")
                    .param("id", seed.id())
                    .query(Long.class)
                    .single();
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
                    .param("authorId", ThemeContentLibrary.authorId(seed.author()))
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
            postTagStore.replace(seed.id(), seed.tags());
            imported++;
        }
        return imported;
    }

    @Transactional
    public Map<String, Object> createAppeal(Long userId, Long postId, String reason, String idempotencyKey) {
        Long existingId = idempotencyStore.claim(userId, "CREATE_APPEAL:" + postId, idempotencyKey,
                IdempotencyFingerprint.of("create-appeal-v1", postId, reason));
        if (existingId != null) {
            AppealState existing = jdbcClient.sql("SELECT post_id, status FROM appeal_record WHERE id = :appealId")
                    .param("appealId", existingId)
                    .query((rs, rowNum) -> new AppealState(rs.getLong("post_id"), rs.getString("status")))
                    .optional()
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "申诉记录不存在"));
            return Map.of("appealId", existingId, "postId", existing.postId(), "status", existing.status(), "duplicateRequest", true);
        }
        Long owned = jdbcClient.sql("""
                        SELECT COUNT(*) FROM post
                        WHERE id = :postId AND author_id = :userId AND status IN ('REJECTED', 'CHANGES_REQUIRED')
                        """)
                .param("postId", postId)
                .param("userId", userId)
                .query(Long.class)
                .single();
        if (owned == null || owned == 0) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只能对自己被驳回或要求修改的投稿发起申诉");
        }
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO appeal_record(post_id, user_id, reason) VALUES (?, ?, ?)",
                    new String[]{"id"}
            );
            statement.setLong(1, postId);
            statement.setLong(2, userId);
            statement.setString(3, reason.trim());
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        Long appealId = key == null ? 0L : key.longValue();
        jdbcClient.sql("INSERT INTO post_audit_record(post_id, auditor_id, action, reason) VALUES (:postId, :userId, 'APPEAL_CREATED', :reason)")
                .param("postId", postId)
                .param("userId", userId)
                .param("reason", reason.trim())
                .update();
        eventStore.append(EventTopics.APPEAL_CREATED, "APPEAL", appealId.toString(),
                Map.of("appealId", appealId, "postId", postId, "userId", userId));
        idempotencyStore.attachResource(idempotencyKey, appealId);
        return Map.of("appealId", appealId, "postId", postId, "status", "PENDING");
    }

    public List<io.github.devcircle.content.api.PostAuditView> listAudits(Long authorId, Long postId) {
        Integer owned = jdbcClient.sql("SELECT COUNT(*) FROM post WHERE id = :postId AND author_id = :authorId")
                .param("postId", postId).param("authorId", authorId).query(Integer.class).single();
        if (owned == null || owned == 0) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "投稿不存在");
        return jdbcClient.sql("""
                        SELECT id, post_id, auditor_id, action, reason, created_at
                        FROM post_audit_record WHERE post_id = :postId ORDER BY created_at ASC, id ASC
                        """)
                .param("postId", postId)
                .query((rs, rowNum) -> new io.github.devcircle.content.api.PostAuditView(
                        rs.getLong("id"), rs.getLong("post_id"), (Long) rs.getObject("auditor_id"),
                        rs.getString("action"), rs.getString("reason"), rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    private record AppealState(long postId, String status) {
    }

    private void writeOutbox(Long postId, Long authorId, CreatePostRequest request) {
        eventStore.append(EventTopics.POST_SUBMITTED, "POST", postId.toString(), Map.of(
                "postId", postId,
                "authorId", authorId,
                "title", request.title(),
                "tags", normalizeTags(request.tags()),
                "theme", normalizeTheme(request.theme()),
                "contentKind", normalizeContentKind(request.contentKind())
        ));
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
            throw new IllegalStateException("Failed to serialize structured draft content", ex);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize structured seed content", ex);
        }
    }

    private List<Long> safeMediaIds(List<Long> mediaIds) {
        return mediaIds == null ? List.of() : mediaIds;
    }

    private String excerpt(String content) {
        return content.substring(0, Math.min(140, content.length()));
    }

}
