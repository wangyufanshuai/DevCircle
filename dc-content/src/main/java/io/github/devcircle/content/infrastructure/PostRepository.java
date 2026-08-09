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
import io.github.devcircle.content.api.DraftMediaInput;
import io.github.devcircle.content.api.DraftView;
import io.github.devcircle.content.api.SaveDraftRequest;
import io.github.devcircle.content.domain.DraftSubmissionValidator;
import io.github.devcircle.content.domain.DraftWorkflowPolicy;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class PostRepository {
    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final PostQueryRepository postQueryRepository;
    private final DraftMediaRepository draftMediaRepository;
    private final ContentIdempotencyStore idempotencyStore;
    private final ContentEventStore eventStore;
    private final ContentModerationStore moderationStore;

    public PostRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                          PostQueryRepository postQueryRepository,
                          DraftMediaRepository draftMediaRepository,
                          ContentIdempotencyStore idempotencyStore,
                          ContentEventStore eventStore,
                          ContentModerationStore moderationStore) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.objectMapper = objectMapper;
        this.postQueryRepository = postQueryRepository;
        this.draftMediaRepository = draftMediaRepository;
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
        replacePostTags(postId, request.tags());
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
                normalizedTags(request.tags()), mediaAssets(postId), 0, 0, 0, "PENDING", Instant.now());
    }

    @Transactional
    public DraftView createDraft(Long authorId, SaveDraftRequest request, String idempotencyKey) {
        Long existingId = idempotencyStore.claim(authorId, "CREATE_DRAFT", idempotencyKey,
                draftFingerprint(request));
        if (existingId != null) {
            return getDraft(authorId, existingId);
        }
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO post(author_id, title, type, theme, content_kind, difficulty, solvable, status, version, last_saved_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'DRAFT', 0, CURRENT_TIMESTAMP)
                    """, new String[]{"id"});
            statement.setLong(1, authorId);
            statement.setString(2, safeText(request.title()));
            statement.setString(3, normalizeType(request.type()));
            statement.setString(4, normalizeTheme(request.theme()));
            statement.setString(5, normalizeContentKind(request.contentKind()));
            statement.setString(6, normalizeDifficulty(request.difficulty()));
            statement.setBoolean(7, Boolean.TRUE.equals(request.solvable()));
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Failed to create draft");
        }
        Long draftId = key.longValue();
        insertDraftContent(draftId, request);
        replaceDraftRelations(draftId, authorId, request.tags(), request.mediaIds(), request.media());
        idempotencyStore.attachResource(idempotencyKey, draftId);
        return getDraft(authorId, draftId);
    }

    public List<DraftView> listDrafts(Long authorId, int size) {
        List<DraftView> drafts = draftQuery("WHERE p.author_id = :authorId")
                .param("authorId", authorId)
                .param("size", size)
                .query(this::mapDraft)
                .list();
        return draftMediaRepository.attach(drafts);
    }

    public DraftView getDraft(Long authorId, Long draftId) {
        List<DraftView> drafts = draftQuery("WHERE p.author_id = :authorId AND p.id = :draftId")
                .param("authorId", authorId)
                .param("draftId", draftId)
                .param("size", 1)
                .query(this::mapDraft)
                .list();
        if (drafts.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "草稿不存在");
        }
        return draftMediaRepository.attach(drafts).getFirst();
    }

    @Transactional
    public DraftView updateDraft(Long authorId, Long draftId, SaveDraftRequest request, String idempotencyKey) {
        Long duplicateId = idempotencyStore.claim(authorId, "UPDATE_DRAFT:" + draftId, idempotencyKey,
                draftFingerprint(request));
        if (duplicateId != null) {
            return getDraft(authorId, duplicateId);
        }
        int updated = jdbcClient.sql("""
                        UPDATE post
                        SET title = :title, type = :type, theme = :theme, content_kind = :contentKind,
                            difficulty = :difficulty, solvable = :solvable, version = version + 1,
                            last_saved_at = CURRENT_TIMESTAMP
                        WHERE id = :draftId AND author_id = :authorId
                          AND status IN ('DRAFT', 'CHANGES_REQUIRED', 'REJECTED') AND version = :version
                        """)
                .param("title", safeText(request.title()))
                .param("type", normalizeType(request.type()))
                .param("theme", normalizeTheme(request.theme()))
                .param("contentKind", normalizeContentKind(request.contentKind()))
                .param("difficulty", normalizeDifficulty(request.difficulty()))
                .param("solvable", Boolean.TRUE.equals(request.solvable()))
                .param("draftId", draftId)
                .param("authorId", authorId)
                .param("version", request.version())
                .update();
        if (updated == 0) {
            assertDraftWritable(authorId, draftId);
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "草稿已在其他位置更新，请刷新后重试");
        }
        updateDraftContent(draftId, request);
        replaceDraftRelations(draftId, authorId, request.tags(), request.mediaIds(), request.media());
        idempotencyStore.attachResource(idempotencyKey, draftId);
        return getDraft(authorId, draftId);
    }

    @Transactional
    public DraftView submitDraft(Long authorId, Long draftId, long version, String idempotencyKey) {
        Long duplicateId = idempotencyStore.claim(authorId, "SUBMIT_DRAFT:" + draftId, idempotencyKey,
                IdempotencyFingerprint.of("submit-draft-v1", draftId, version));
        if (duplicateId != null) {
            return getDraft(authorId, duplicateId);
        }
        DraftView draft = getDraft(authorId, draftId);
        DraftSubmissionValidator.validate(draft);
        draftMediaRepository.requireSubmittable(authorId, draftId);
        int updated = jdbcClient.sql("""
                        UPDATE post
                        SET status = 'PENDING', version = version + 1, submitted_at = CURRENT_TIMESTAMP, review_reason = NULL
                        WHERE id = :draftId AND author_id = :authorId
                          AND status IN ('DRAFT', 'CHANGES_REQUIRED', 'REJECTED') AND version = :version
                        """)
                .param("draftId", draftId)
                .param("authorId", authorId)
                .param("version", version)
                .update();
        if (updated == 0) {
            assertDraftWritable(authorId, draftId);
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "草稿版本已变化，请刷新后重新提交");
        }
        String submissionAction = DraftWorkflowPolicy.submissionAuditAction(draft.status());
        jdbcClient.sql("INSERT INTO post_audit_record(post_id, auditor_id, action, reason) VALUES (:postId, NULL, :action, :reason)")
                .param("postId", draftId)
                .param("action", submissionAction)
                .param("reason", "SUBMIT_POST".equals(submissionAction) ? "作者提交审核" : "作者修改后重新提交审核")
                .update();
        CreatePostRequest submitted = new CreatePostRequest(
                draft.title(), draft.content(), draft.tags(), draft.type(), draft.mediaIds(), draft.theme(),
                draft.contentKind(), draft.difficulty(), draft.solvable(), draft.clues(), draft.solution());
        moderationStore.createTask(draftId, authorId, submitted);
        eventStore.append(EventTopics.POST_SUBMITTED, "POST", draftId.toString(), Map.of(
                "postId", draftId, "authorId", authorId, "title", draft.title(),
                "theme", draft.theme(), "contentKind", draft.contentKind()));
        idempotencyStore.attachResource(idempotencyKey, draftId);
        return getDraft(authorId, draftId);
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
            replacePostTags(seed.id(), seed.tags());
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

    private JdbcClient.StatementSpec draftQuery(String whereClause) {
        return jdbcClient.sql("""
                        SELECT p.id, p.author_id, p.title, p.type, p.theme, p.content_kind, p.difficulty,
                               p.solvable, p.status, p.version, p.last_saved_at, p.submitted_at, p.review_reason,
                               pc.markdown, pc.clues, pc.suspects, pc.timeline, pc.question,
                               pc.answer_keywords, pc.solution,
                               GROUP_CONCAT(DISTINCT t.name ORDER BY t.name SEPARATOR ',') AS tags,
                               GROUP_CONCAT(DISTINCT pm.media_id ORDER BY pm.sort_order SEPARATOR ',') AS media_ids
                        FROM post p
                        JOIN post_content pc ON pc.post_id = p.id
                        LEFT JOIN post_tag pt ON pt.post_id = p.id
                        LEFT JOIN tag t ON t.id = pt.tag_id
                        LEFT JOIN post_media pm ON pm.post_id = p.id
                        """ + whereClause + "\n" + """
                        GROUP BY p.id, p.author_id, p.title, p.type, p.theme, p.content_kind, p.difficulty,
                                 p.solvable, p.status, p.version, p.last_saved_at, p.submitted_at, p.review_reason,
                                 pc.markdown, pc.clues, pc.suspects, pc.timeline, pc.question,
                                 pc.answer_keywords, pc.solution
                        ORDER BY COALESCE(p.last_saved_at, p.updated_at) DESC
                        LIMIT :size
                        """);
    }

    private DraftView mapDraft(ResultSet rs, int rowNum) throws SQLException {
        return new DraftView(
                rs.getLong("id"),
                rs.getLong("author_id"),
                safeText(rs.getString("title")),
                safeText(rs.getString("markdown")),
                splitTags(rs.getString("tags")),
                rs.getString("type"),
                readLongCsv(rs.getString("media_ids")),
                List.of(),
                rs.getString("theme"),
                rs.getString("content_kind"),
                rs.getString("difficulty"),
                rs.getBoolean("solvable"),
                String.join("\n", readStringList(rs.getString("clues"))),
                String.join("\n", readStringList(rs.getString("suspects"))),
                String.join("\n", readStringList(rs.getString("timeline"))),
                safeText(rs.getString("question")),
                String.join("\n", readStringList(rs.getString("answer_keywords"))),
                safeText(rs.getString("solution")),
                rs.getString("status"),
                rs.getLong("version"),
                rs.getTimestamp("last_saved_at") == null ? null : rs.getTimestamp("last_saved_at").toInstant(),
                rs.getTimestamp("submitted_at") == null ? null : rs.getTimestamp("submitted_at").toInstant(),
                rs.getString("review_reason")
        );
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

    private void insertDraftContent(Long draftId, SaveDraftRequest request) {
        jdbcClient.sql("""
                        INSERT INTO post_content(post_id, markdown, clues, suspects, timeline, question,
                                                 answer_keywords, solution, explanation)
                        VALUES (:postId, :markdown, CAST(:clues AS JSON), CAST(:suspects AS JSON),
                                CAST(:timeline AS JSON), :question, CAST(:answerKeywords AS JSON), :solution, :explanation)
                        """)
                .param("postId", draftId)
                .param("markdown", safeText(request.content()))
                .param("clues", cluesJson(request.clues()))
                .param("suspects", cluesJson(request.suspects()))
                .param("timeline", cluesJson(request.timeline()))
                .param("question", safeText(request.question()))
                .param("answerKeywords", cluesJson(request.answerKeywords()))
                .param("solution", safeText(request.solution()))
                .param("explanation", safeText(request.solution()))
                .update();
    }

    private void updateDraftContent(Long draftId, SaveDraftRequest request) {
        jdbcClient.sql("""
                        UPDATE post_content
                        SET markdown = :markdown, clues = CAST(:clues AS JSON), suspects = CAST(:suspects AS JSON),
                            timeline = CAST(:timeline AS JSON), question = :question,
                            answer_keywords = CAST(:answerKeywords AS JSON), solution = :solution, explanation = :explanation
                        WHERE post_id = :postId
                        """)
                .param("postId", draftId)
                .param("markdown", safeText(request.content()))
                .param("clues", cluesJson(request.clues()))
                .param("suspects", cluesJson(request.suspects()))
                .param("timeline", cluesJson(request.timeline()))
                .param("question", safeText(request.question()))
                .param("answerKeywords", cluesJson(request.answerKeywords()))
                .param("solution", safeText(request.solution()))
                .param("explanation", safeText(request.solution()))
                .update();
    }

    private void replaceDraftRelations(Long draftId, Long authorId, List<String> tags, List<Long> mediaIds,
                                       List<DraftMediaInput> media) {
        replacePostTags(draftId, tags);
        draftMediaRepository.replace(draftId, authorId, mediaIds, media);
    }

    private record AppealState(long postId, String status) {
    }

    private String draftFingerprint(SaveDraftRequest request) {
        return IdempotencyFingerprint.of("draft-v1", request.title(), request.content(), request.tags(), request.type(),
                request.mediaIds(), request.media(), request.theme(), request.contentKind(), request.difficulty(),
                request.solvable(), request.clues(), request.suspects(), request.timeline(), request.question(),
                request.answerKeywords(), request.solution(), request.version());
    }

    private void assertDraftWritable(Long authorId, Long draftId) {
        List<String> statuses = jdbcClient.sql("SELECT status FROM post WHERE id = :draftId AND author_id = :authorId")
                .param("draftId", draftId)
                .param("authorId", authorId)
                .query(String.class)
                .list();
        if (statuses.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "草稿不存在");
        }
        if (!DraftWorkflowPolicy.isAuthorEditable(statuses.getFirst())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "当前状态不允许编辑或重复提交");
        }
    }

    private void replacePostTags(Long postId, List<String> tags) {
        List<Long> existingTagIds = jdbcClient.sql("SELECT tag_id FROM post_tag WHERE post_id = :postId")
                .param("postId", postId)
                .query(Long.class)
                .list();
        if (!existingTagIds.isEmpty()) {
            jdbcClient.sql("DELETE FROM post_tag WHERE post_id = :postId")
                    .param("postId", postId)
                    .update();
            jdbcClient.sql("UPDATE tag SET use_count = GREATEST(use_count - 1, 0) WHERE id IN (:tagIds)")
                    .param("tagIds", existingTagIds)
                    .update();
        }
        for (String tagName : normalizedTags(tags)) {
            attachTag(postId, tagName);
        }
    }

    private void attachTag(Long postId, String name) {
        Long tagId = ensureTag(name);
        int inserted = jdbcClient.sql("INSERT IGNORE INTO post_tag(post_id, tag_id) VALUES (:postId, :tagId)")
                .param("postId", postId)
                .param("tagId", tagId)
                .update();
        if (inserted == 1) {
            jdbcClient.sql("UPDATE tag SET use_count = use_count + 1 WHERE id = :tagId")
                    .param("tagId", tagId)
                    .update();
        }
    }

    private Long ensureTag(String name) {
        jdbcClient.sql("INSERT IGNORE INTO tag(name, use_count) VALUES (:name, 0)")
                .param("name", name)
                .update();
        return jdbcClient.sql("SELECT id FROM tag WHERE name = :name")
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private void writeOutbox(Long postId, Long authorId, CreatePostRequest request) {
        eventStore.append(EventTopics.POST_SUBMITTED, "POST", postId.toString(), Map.of(
                "postId", postId,
                "authorId", authorId,
                "title", request.title(),
                "tags", normalizedTags(request.tags()),
                "theme", normalizeTheme(request.theme()),
                "contentKind", normalizeContentKind(request.contentKind())
        ));
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

    private List<Long> readLongCsv(String values) {
        if (values == null || values.isBlank()) {
            return List.of();
        }
        return Arrays.stream(values.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(Long::valueOf)
                .toList();
    }

    private List<String> safeTags(List<String> tags) {
        return tags == null ? List.of() : tags;
    }

    private List<String> normalizedTags(List<String> tags) {
        return safeTags(tags).stream()
                .filter(tag -> tag != null)
                .map(String::trim)
                .filter(tag -> !tag.isBlank())
                .distinct()
                .limit(10)
                .toList();
    }

    private List<Long> safeMediaIds(List<Long> mediaIds) {
        return mediaIds == null ? List.of() : mediaIds;
    }

    private String excerpt(String content) {
        return content.substring(0, Math.min(140, content.length()));
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

}
