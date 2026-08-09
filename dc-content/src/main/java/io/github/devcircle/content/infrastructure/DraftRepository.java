package io.github.devcircle.content.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.content.api.CreatePostRequest;
import io.github.devcircle.content.api.DraftView;
import io.github.devcircle.content.api.SaveDraftRequest;
import io.github.devcircle.content.domain.DraftSubmissionValidator;
import io.github.devcircle.content.domain.DraftWorkflowPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static io.github.devcircle.content.domain.PostMetadataPolicy.normalizeContentKind;
import static io.github.devcircle.content.domain.PostMetadataPolicy.normalizeDifficulty;
import static io.github.devcircle.content.domain.PostMetadataPolicy.normalizeTheme;
import static io.github.devcircle.content.domain.PostMetadataPolicy.normalizeType;

/** Persistence boundary for the complete author draft lifecycle. */
@Repository
public class DraftRepository {
    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final DraftMediaRepository draftMediaRepository;
    private final PostTagStore postTagStore;
    private final ContentIdempotencyStore idempotencyStore;
    private final ContentEventStore eventStore;
    private final ContentModerationStore moderationStore;
    private final DraftReadRepository readRepository;

    @Autowired
    public DraftRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                           DraftMediaRepository draftMediaRepository, PostTagStore postTagStore,
                           ContentIdempotencyStore idempotencyStore, ContentEventStore eventStore,
                           ContentModerationStore moderationStore, DraftReadRepository readRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.objectMapper = objectMapper;
        this.draftMediaRepository = draftMediaRepository;
        this.postTagStore = postTagStore;
        this.idempotencyStore = idempotencyStore;
        this.eventStore = eventStore;
        this.moderationStore = moderationStore;
        this.readRepository = readRepository;
    }

    /** Compatibility constructor retained for repository tests during aggregate migration. */
    public DraftRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                           DraftMediaRepository draftMediaRepository, PostTagStore postTagStore,
                           ContentIdempotencyStore idempotencyStore, ContentEventStore eventStore,
                           ContentModerationStore moderationStore) {
        this(jdbcTemplate, objectMapper, draftMediaRepository, postTagStore, idempotencyStore, eventStore,
                moderationStore, new DraftReadRepository(jdbcTemplate, objectMapper, draftMediaRepository));
    }

    @Transactional
    public DraftView create(Long authorId, SaveDraftRequest request, String idempotencyKey) {
        Long existingId = idempotencyStore.claim(authorId, "CREATE_DRAFT", idempotencyKey,
                fingerprint(request));
        if (existingId != null) {
            return get(authorId, existingId);
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
        insertContent(draftId, request);
        replaceRelations(draftId, authorId, request);
        idempotencyStore.attachResource(idempotencyKey, draftId);
        return get(authorId, draftId);
    }

    public List<DraftView> list(Long authorId, int size) {
        return readRepository.list(authorId, size);
    }

    public DraftView get(Long authorId, Long draftId) {
        return readRepository.get(authorId, draftId);
    }

    @Transactional
    public DraftView update(Long authorId, Long draftId, SaveDraftRequest request, String idempotencyKey) {
        Long duplicateId = idempotencyStore.claim(authorId, "UPDATE_DRAFT:" + draftId, idempotencyKey,
                fingerprint(request));
        if (duplicateId != null) {
            return get(authorId, duplicateId);
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
            assertWritable(authorId, draftId);
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "草稿已在其他位置更新，请刷新后重试");
        }
        updateContent(draftId, request);
        replaceRelations(draftId, authorId, request);
        idempotencyStore.attachResource(idempotencyKey, draftId);
        return get(authorId, draftId);
    }

    @Transactional
    public DraftView submit(Long authorId, Long draftId, long version, String idempotencyKey) {
        Long duplicateId = idempotencyStore.claim(authorId, "SUBMIT_DRAFT:" + draftId, idempotencyKey,
                IdempotencyFingerprint.of("submit-draft-v1", draftId, version));
        if (duplicateId != null) {
            return get(authorId, duplicateId);
        }
        DraftView draft = get(authorId, draftId);
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
            assertWritable(authorId, draftId);
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
        return get(authorId, draftId);
    }

    private void insertContent(Long draftId, SaveDraftRequest request) {
        jdbcClient.sql("""
                        INSERT INTO post_content(post_id, markdown, clues, suspects, timeline, question,
                                                 answer_keywords, solution, explanation)
                        VALUES (:postId, :markdown, CAST(:clues AS JSON), CAST(:suspects AS JSON),
                                CAST(:timeline AS JSON), :question, CAST(:answerKeywords AS JSON), :solution, :explanation)
                        """)
                .param("postId", draftId)
                .param("markdown", safeText(request.content()))
                .param("clues", structuredJson(request.clues()))
                .param("suspects", structuredJson(request.suspects()))
                .param("timeline", structuredJson(request.timeline()))
                .param("question", safeText(request.question()))
                .param("answerKeywords", structuredJson(request.answerKeywords()))
                .param("solution", safeText(request.solution()))
                .param("explanation", safeText(request.solution()))
                .update();
    }

    private void updateContent(Long draftId, SaveDraftRequest request) {
        jdbcClient.sql("""
                        UPDATE post_content
                        SET markdown = :markdown, clues = CAST(:clues AS JSON), suspects = CAST(:suspects AS JSON),
                            timeline = CAST(:timeline AS JSON), question = :question,
                            answer_keywords = CAST(:answerKeywords AS JSON), solution = :solution, explanation = :explanation
                        WHERE post_id = :postId
                        """)
                .param("postId", draftId)
                .param("markdown", safeText(request.content()))
                .param("clues", structuredJson(request.clues()))
                .param("suspects", structuredJson(request.suspects()))
                .param("timeline", structuredJson(request.timeline()))
                .param("question", safeText(request.question()))
                .param("answerKeywords", structuredJson(request.answerKeywords()))
                .param("solution", safeText(request.solution()))
                .param("explanation", safeText(request.solution()))
                .update();
    }

    private void replaceRelations(Long draftId, Long authorId, SaveDraftRequest request) {
        postTagStore.replace(draftId, request.tags());
        draftMediaRepository.replace(draftId, authorId, request.mediaIds(), request.media());
    }

    private String fingerprint(SaveDraftRequest request) {
        return IdempotencyFingerprint.of("draft-v1", request.title(), request.content(), request.tags(), request.type(),
                request.mediaIds(), request.media(), request.theme(), request.contentKind(), request.difficulty(),
                request.solvable(), request.clues(), request.suspects(), request.timeline(), request.question(),
                request.answerKeywords(), request.solution(), request.version());
    }

    private void assertWritable(Long authorId, Long draftId) {
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

    private String structuredJson(String value) {
        try {
            if (value == null || value.isBlank()) {
                return "[]";
            }
            List<String> values = Arrays.stream(value.split("\\n|;|；"))
                    .map(String::trim)
                    .filter(item -> !item.isBlank())
                    .toList();
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize structured draft content", ex);
        }
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }
}
