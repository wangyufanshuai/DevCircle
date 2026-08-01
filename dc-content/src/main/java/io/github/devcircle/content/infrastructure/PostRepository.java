package io.github.devcircle.content.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.seed.ThemeContentLibrary;
import io.github.devcircle.common.seed.ThemeSeedPost;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.common.event.EventEnvelope;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.common.model.MediaAssetView;
import io.github.devcircle.common.model.PostSummary;
import io.github.devcircle.content.api.CreatePostRequest;
import io.github.devcircle.content.api.DraftMediaInput;
import io.github.devcircle.content.api.DraftMediaView;
import io.github.devcircle.content.api.DraftView;
import io.github.devcircle.content.api.FavoritePostView;
import io.github.devcircle.content.api.PostDetailView;
import io.github.devcircle.content.api.PostCursorPage;
import io.github.devcircle.content.api.ReadingProgressRequest;
import io.github.devcircle.content.api.ReadingProgressView;
import io.github.devcircle.content.api.SaveDraftRequest;
import io.github.devcircle.content.domain.DraftSubmissionValidator;
import io.github.devcircle.content.domain.DraftWorkflowPolicy;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
                        WHERE p.status = 'PUBLISHED'
                          AND (:theme IS NULL OR p.theme = :theme)
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
                        rs.getLong("author_id"),
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
                        rs.getLong("author_id"),
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

    public PostDetailView publicDetail(Long id, Long viewerId) {
        DeductionSnapshot deduction = deductionSnapshot(viewerId, id);
        ReadingProgressView progress = readingProgress(viewerId, id);
        return jdbcClient.sql("""
                        SELECT p.id, p.type, p.theme, p.content_kind, p.difficulty, p.solvable, p.solved_count,
                               p.title, pc.markdown, pc.solution, p.author_id, p.status,
                               p.like_count, p.comment_count, p.view_count, p.created_at,
                               pc.clues, pc.suspects, pc.timeline, pc.question,
                               pc.worldbuilding, pc.cosmic_facts, pc.related_post_ids,
                               GROUP_CONCAT(t.name ORDER BY t.name SEPARATOR ',') AS tags
                        FROM post p
                        JOIN post_content pc ON pc.post_id = p.id
                        LEFT JOIN post_tag pt ON pt.post_id = p.id
                        LEFT JOIN tag t ON t.id = pt.tag_id
                        WHERE p.id = :id AND p.status = 'PUBLISHED'
                        GROUP BY p.id, p.type, p.theme, p.content_kind, p.difficulty, p.solvable, p.solved_count,
                                 p.title, pc.markdown, pc.solution, p.author_id, p.status,
                                 p.like_count, p.comment_count, p.view_count, p.created_at,
                                 pc.clues, pc.suspects, pc.timeline, pc.question,
                                 pc.worldbuilding, pc.cosmic_facts, pc.related_post_ids
                        """)
                .param("id", id)
                .query((rs, rowNum) -> {
                    String markdown = Optional.ofNullable(rs.getString("markdown")).orElse("");
                    long authorId = rs.getLong("author_id");
                    boolean unlocked = deduction.solved() || deduction.revealed();
                    return new PostDetailView(
                            rs.getLong("id"), rs.getString("type"), rs.getString("theme"),
                            rs.getString("content_kind"), rs.getString("difficulty"),
                            rs.getBoolean("solvable"), rs.getLong("solved_count"),
                            rs.getString("title"), excerpt(markdown), markdown,
                            new PostDetailView.AuthorView(authorId, "user-" + authorId, null, null, null, null),
                            splitTags(rs.getString("tags")), publicMediaAssets(rs.getLong("id")),
                            rs.getLong("like_count"), rs.getLong("comment_count"), rs.getLong("view_count"),
                            rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                            readStringList(rs.getString("clues")), readStringList(rs.getString("suspects")),
                            readStringList(rs.getString("timeline")), Optional.ofNullable(rs.getString("question")).orElse(""),
                            readStringList(rs.getString("worldbuilding")), readStringList(rs.getString("cosmic_facts")),
                            readLongList(rs.getString("related_post_ids")),
                            new PostDetailView.ViewerState(false, false, progress.progressPercent(),
                                    progress.anchor(), progress.completed()),
                            new PostDetailView.DeductionState(deduction.attempted(), deduction.solved(), deduction.revealed(),
                                    deduction.points(), unlocked),
                            unlocked ? Optional.ofNullable(rs.getString("solution")).orElse("") : null
                    );
                })
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "内容不存在或尚未发布"));
    }

    public List<FavoritePostView> publicSummaries(List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) return List.of();
        List<Long> safeIds = postIds.stream().filter(id -> id != null && id > 0).distinct().limit(50).toList();
        if (safeIds.isEmpty()) return List.of();
        return jdbcClient.sql("""
                        SELECT p.id, p.title, LEFT(pc.markdown, 180) AS excerpt, p.theme, p.content_kind,
                               p.difficulty, p.created_at
                        FROM post p
                        JOIN post_content pc ON pc.post_id = p.id
                        WHERE p.id IN (:postIds) AND p.status = 'PUBLISHED'
                        ORDER BY p.updated_at DESC, p.id DESC
                        """)
                .param("postIds", safeIds)
                .query((rs, rowNum) -> new FavoritePostView(rs.getLong("id"), rs.getString("title"),
                        safeText(rs.getString("excerpt")), rs.getString("theme"), rs.getString("content_kind"),
                        rs.getString("difficulty"), rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    @Transactional
    public ReadingProgressView saveReadingProgress(Long userId, Long postId, ReadingProgressRequest request,
                                                   String idempotencyKey) {
        Integer published = jdbcClient.sql("SELECT COUNT(*) FROM post WHERE id = :postId AND status = 'PUBLISHED'")
                .param("postId", postId).query(Integer.class).single();
        if (published == null || published == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "内容不存在或尚未发布");
        }
        int claimed = jdbcClient.sql("""
                        INSERT IGNORE INTO reading_progress_idempotency(idempotency_key, user_id, post_id)
                        VALUES (:key, :userId, :postId)
                        """)
                .param("key", idempotencyKey).param("userId", userId).param("postId", postId).update();
        if (claimed == 0) {
            ProgressIdempotency existing = jdbcClient.sql("""
                            SELECT user_id, post_id FROM reading_progress_idempotency WHERE idempotency_key = :key
                            """)
                    .param("key", idempotencyKey)
                    .query((rs, rowNum) -> new ProgressIdempotency(rs.getLong("user_id"), rs.getLong("post_id")))
                    .single();
            if (!existing.userId().equals(userId) || !existing.postId().equals(postId)) {
                throw new BusinessException(ErrorCode.DUPLICATE_OPERATION, "Idempotency-Key 已用于其他阅读进度请求");
            }
            return getReadingProgress(userId, postId);
        }
        int progress = request.completed() ? 100 : Math.max(0, Math.min(request.progressPercent(), 99));
        jdbcClient.sql("""
                        INSERT IGNORE INTO reading_progress(user_id, post_id, progress_percent, anchor, completed)
                        VALUES (:userId, :postId, :progress, :anchor, :completed)
                        """)
                .param("userId", userId).param("postId", postId).param("progress", progress)
                .param("anchor", request.anchor()).param("completed", request.completed()).update();
        jdbcClient.sql("""
                        UPDATE reading_progress
                        SET anchor = IF(:progress >= progress_percent, :anchor, anchor),
                            progress_percent = GREATEST(progress_percent, :progress),
                            completed = completed OR :completed,
                            last_read_at = CURRENT_TIMESTAMP
                        WHERE user_id = :userId AND post_id = :postId
                        """)
                .param("userId", userId).param("postId", postId).param("progress", progress)
                .param("anchor", request.anchor()).param("completed", request.completed()).update();
        return getReadingProgress(userId, postId);
    }

    public ReadingProgressView getReadingProgress(Long userId, Long postId) {
        return jdbcClient.sql("""
                        SELECT post_id, progress_percent, anchor, completed, last_read_at
                        FROM reading_progress WHERE user_id = :userId AND post_id = :postId
                        """)
                .param("userId", userId).param("postId", postId)
                .query((rs, rowNum) -> new ReadingProgressView(rs.getLong("post_id"),
                        rs.getInt("progress_percent"), rs.getString("anchor"), rs.getBoolean("completed"),
                        rs.getTimestamp("last_read_at").toInstant()))
                .optional()
                .orElse(new ReadingProgressView(postId, 0, null, false, null));
    }

    public List<io.github.devcircle.content.api.ReadingProgressSummary> listReadingProgress(Long userId, int size) {
        return jdbcClient.sql("""
                        SELECT rp.post_id,
                               CASE WHEN p.id IS NULL OR p.status <> 'PUBLISHED' THEN '内容已不可用' ELSE p.title END AS title,
                               CASE WHEN p.status = 'PUBLISHED' THEN p.theme ELSE NULL END AS theme,
                               rp.progress_percent, rp.completed, rp.last_read_at,
                               CASE WHEN p.status = 'PUBLISHED' THEN TRUE ELSE FALSE END AS available,
                               CASE
                                 WHEN p.id IS NULL OR p.status <> 'PUBLISHED' THEN 'UNAVAILABLE'
                                 WHEN COALESCE(da.revealed, FALSE) THEN 'REVEALED'
                                 WHEN COALESCE(da.correct, FALSE) THEN 'SOLVED'
                                 WHEN rp.completed THEN 'COMPLETED'
                                 ELSE 'IN_PROGRESS'
                               END AS track_state
                        FROM reading_progress rp
                        LEFT JOIN post p ON p.id = rp.post_id
                        LEFT JOIN deduction_attempt da ON da.user_id = rp.user_id AND da.post_id = rp.post_id
                        WHERE rp.user_id = :userId
                        ORDER BY CASE
                                   WHEN p.status = 'PUBLISHED' AND NOT rp.completed
                                        AND NOT COALESCE(da.correct, FALSE) AND NOT COALESCE(da.revealed, FALSE)
                                   THEN 0 ELSE 1
                                 END,
                                 rp.last_read_at DESC
                        LIMIT :size
                        """)
                .param("userId", userId).param("size", Math.max(1, Math.min(size, 50)))
                .query((rs, rowNum) -> new io.github.devcircle.content.api.ReadingProgressSummary(
                        rs.getLong("post_id"), rs.getString("title"), rs.getString("theme"),
                        rs.getInt("progress_percent"), rs.getBoolean("completed"), rs.getTimestamp("last_read_at").toInstant(),
                        rs.getBoolean("available"), rs.getString("track_state")))
                .list();
    }

    public PostCursorPage listCursor(String cursor, int size, String theme, String contentKind, String difficulty) {
        CursorValue value = decodeCursor(cursor);
        List<PostSummary> rows = jdbcClient.sql("""
                        SELECT p.id, p.type, p.theme, p.content_kind, p.difficulty, p.solvable, p.solved_count,
                               p.title, LEFT(pc.markdown, 140) AS excerpt, p.author_id, p.status,
                               p.like_count, p.comment_count, p.view_count, p.created_at,
                               GROUP_CONCAT(t.name ORDER BY t.name SEPARATOR ',') AS tags
                        FROM post p
                        JOIN post_content pc ON pc.post_id = p.id
                        LEFT JOIN post_tag pt ON pt.post_id = p.id
                        LEFT JOIN tag t ON t.id = pt.tag_id
                        WHERE p.status = 'PUBLISHED'
                          AND (:theme IS NULL OR p.theme = :theme)
                          AND (:contentKind IS NULL OR p.content_kind = :contentKind)
                          AND (:difficulty IS NULL OR p.difficulty = :difficulty)
                          AND (:cursorTime IS NULL OR p.created_at < :cursorTime
                               OR (p.created_at = :cursorTime AND p.id < :cursorId))
                        GROUP BY p.id, p.type, p.theme, p.content_kind, p.difficulty, p.solvable, p.solved_count,
                                 p.title, pc.markdown, p.author_id, p.status, p.like_count, p.comment_count,
                                 p.view_count, p.created_at
                        ORDER BY p.created_at DESC, p.id DESC
                        LIMIT :limit
                        """)
                .param("theme", normalizeNullableTheme(theme))
                .param("contentKind", normalizeNullableContentKind(contentKind))
                .param("difficulty", normalizeNullableDifficulty(difficulty))
                .param("cursorTime", value == null ? null : java.sql.Timestamp.from(value.createdAt()))
                .param("cursorId", value == null ? Long.MAX_VALUE : value.id())
                .param("limit", size + 1)
                .query((rs, rowNum) -> new PostSummary(
                        rs.getLong("id"), rs.getString("type"), rs.getString("theme"), rs.getString("content_kind"),
                        rs.getString("difficulty"), rs.getBoolean("solvable"), rs.getLong("solved_count"),
                        rs.getString("title"), rs.getString("excerpt"), "user-" + rs.getLong("author_id"),
                        rs.getLong("author_id"), splitTags(rs.getString("tags")), mediaAssets(rs.getLong("id")), rs.getLong("like_count"),
                        rs.getLong("comment_count"), rs.getLong("view_count"), rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
        boolean hasMore = rows.size() > size;
        List<PostSummary> page = hasMore ? rows.subList(0, size) : rows;
        String next = page.isEmpty() || !hasMore ? "" : encodeCursor(page.getLast().createdAt(), page.getLast().id());
        return new PostCursorPage(List.copyOf(page), next, hasMore);
    }

    private CursorValue decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] values = decoded.split("\\|", 2);
            return new CursorValue(Instant.parse(values[0]), Long.parseLong(values[1]));
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "分页游标无效");
        }
    }

    private String encodeCursor(Instant createdAt, Long id) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((createdAt + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    private record CursorValue(Instant createdAt, Long id) {
    }

    public List<io.github.devcircle.content.api.SubmissionView> listSubmissions(Long userId, int size) {
        return jdbcClient.sql("""
                        SELECT id, title, status, updated_at, review_reason FROM post
                        WHERE author_id = :userId
                        ORDER BY updated_at DESC LIMIT :size
                        """)
                .param("userId", userId).param("size", Math.max(1, Math.min(size, 50)))
                .query((rs, rowNum) -> new io.github.devcircle.content.api.SubmissionView(
                        rs.getLong("id"), rs.getString("title"), rs.getString("status"),
                        rs.getTimestamp("updated_at").toInstant(), rs.getString("review_reason")))
                .list();
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
                        rs.getLong("author_id"),
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
        return create(authorId, request, "internal-" + UUID.randomUUID());
    }

    @Transactional
    public PostSummary create(Long authorId, CreatePostRequest request, String idempotencyKey) {
        Long existingId = claimIdempotency(authorId, "CREATE_POST", idempotencyKey);
        if (existingId != null) {
            return detail(existingId);
        }
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
            requireOwnedCompletedMedia(authorId, mediaId);
            jdbcClient.sql("INSERT IGNORE INTO post_media(post_id, media_id, sort_order) VALUES (:postId, :mediaId, :sortOrder)")
                    .param("postId", postId)
                    .param("mediaId", mediaId)
                    .param("sortOrder", index++)
                    .update();
        }
        createModerationTask(postId, authorId, request);
        writeOutbox(postId, authorId, request);
        attachIdempotencyResource(idempotencyKey, postId);
        return new PostSummary(postId, normalizeType(request.type()), normalizeTheme(request.theme()), normalizeContentKind(request.contentKind()),
                normalizeDifficulty(request.difficulty()), Boolean.TRUE.equals(request.solvable()), 0,
                request.title(), excerpt(request.content()), "user-" + authorId, authorId,
                safeTags(request.tags()), mediaAssets(postId), 0, 0, 0, "PENDING", Instant.now());
    }

    @Transactional
    public DraftView createDraft(Long authorId, SaveDraftRequest request, String idempotencyKey) {
        Long existingId = claimIdempotency(authorId, "CREATE_DRAFT", idempotencyKey);
        if (existingId != null) {
            return getDraft(authorId, existingId);
        }
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO post(author_id, title, type, theme, content_kind, difficulty, solvable, status, version, last_saved_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'DRAFT', 0, CURRENT_TIMESTAMP)
                    """, Statement.RETURN_GENERATED_KEYS);
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
        attachIdempotencyResource(idempotencyKey, draftId);
        return getDraft(authorId, draftId);
    }

    public List<DraftView> listDrafts(Long authorId, int size) {
        List<DraftView> drafts = draftQuery("WHERE p.author_id = :authorId")
                .param("authorId", authorId)
                .param("size", size)
                .query(this::mapDraft)
                .list();
        return attachDraftMedia(drafts);
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
        return attachDraftMedia(drafts).getFirst();
    }

    @Transactional
    public DraftView updateDraft(Long authorId, Long draftId, SaveDraftRequest request, String idempotencyKey) {
        Long duplicateId = claimIdempotency(authorId, "UPDATE_DRAFT:" + draftId, idempotencyKey);
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
        attachIdempotencyResource(idempotencyKey, draftId);
        return getDraft(authorId, draftId);
    }

    @Transactional
    public DraftView submitDraft(Long authorId, Long draftId, long version, String idempotencyKey) {
        Long duplicateId = claimIdempotency(authorId, "SUBMIT_DRAFT:" + draftId, idempotencyKey);
        if (duplicateId != null) {
            return getDraft(authorId, duplicateId);
        }
        DraftView draft = getDraft(authorId, draftId);
        DraftSubmissionValidator.validate(draft);
        requireSubmittableMedia(authorId, draftId);
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
        createModerationTask(draftId, authorId, submitted);
        writeOutbox(EventTopics.POST_SUBMITTED, "POST", draftId.toString(), Map.of(
                "postId", draftId, "authorId", authorId, "title", draft.title(),
                "theme", draft.theme(), "contentKind", draft.contentKind()));
        attachIdempotencyResource(idempotencyKey, draftId);
        return getDraft(authorId, draftId);
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
    public Map<String, Object> createAppeal(Long userId, Long postId, String reason, String idempotencyKey) {
        Long existingId = claimIdempotency(userId, "CREATE_APPEAL:" + postId, idempotencyKey);
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
                    Statement.RETURN_GENERATED_KEYS
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
        writeOutbox(EventTopics.APPEAL_CREATED, "APPEAL", appealId.toString(), Map.of("appealId", appealId, "postId", postId, "userId", userId));
        attachIdempotencyResource(idempotencyKey, appealId);
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
        jdbcClient.sql("DELETE FROM post_tag WHERE post_id = :postId").param("postId", draftId).update();
        for (String tagName : safeTags(tags).stream().map(String::trim).filter(tag -> !tag.isBlank()).distinct().limit(10).toList()) {
            Long tagId = upsertTag(tagName);
            jdbcClient.sql("INSERT INTO post_tag(post_id, tag_id) VALUES (:postId, :tagId)")
                    .param("postId", draftId).param("tagId", tagId).update();
        }
        jdbcClient.sql("DELETE FROM post_media WHERE post_id = :postId").param("postId", draftId).update();
        List<DraftMediaInput> items = media == null || media.isEmpty()
                ? safeMediaIds(mediaIds).stream().distinct().limit(20)
                    .map(id -> new DraftMediaInput(id, "INLINE", null, null)).toList()
                : media.stream().limit(20).toList();
        int sortOrder = 0;
        for (DraftMediaInput item : items) {
            requireOwnedCompletedMedia(authorId, item.mediaId());
            String role = "COVER".equals(item.role()) ? "COVER" : "INLINE";
            String altText = item.altText() == null ? null : item.altText().trim();
            jdbcClient.sql("""
                            INSERT INTO post_media(post_id, media_id, role, alt_text, sort_order)
                            VALUES (:postId, :mediaId, :role, :altText, :sortOrder)
                            """)
                    .param("postId", draftId).param("mediaId", item.mediaId()).param("role", role)
                    .param("altText", altText).param("sortOrder", sortOrder++).update();
        }
    }

    private List<DraftView> attachDraftMedia(List<DraftView> drafts) {
        if (drafts.isEmpty()) return drafts;
        List<Long> postIds = drafts.stream().map(DraftView::id).toList();
        List<DraftMediaRow> rows = jdbcClient.sql("""
                        SELECT pm.post_id, pm.media_id, pm.role, pm.alt_text, pm.sort_order, m.content_type
                        FROM post_media pm
                        JOIN media_asset m ON m.id = pm.media_id
                        WHERE pm.post_id IN (:postIds)
                        ORDER BY pm.post_id, pm.sort_order, pm.media_id
                        """)
                .param("postIds", postIds)
                .query((rs, rowNum) -> new DraftMediaRow(rs.getLong("post_id"),
                        new DraftMediaView(rs.getLong("media_id"), rs.getString("role"),
                                safeText(rs.getString("alt_text")), rs.getInt("sort_order"),
                                rs.getString("content_type"))))
                .list();
        Map<Long, List<DraftMediaView>> mediaByPost = new LinkedHashMap<>();
        for (DraftMediaRow row : rows) {
            mediaByPost.computeIfAbsent(row.postId(), ignored -> new ArrayList<>()).add(row.media());
        }
        return drafts.stream().map(draft -> draft.withMedia(mediaByPost.getOrDefault(draft.id(), List.of()))).toList();
    }

    private void requireOwnedCompletedMedia(Long authorId, Long mediaId) {
        requireMediaUploadContract();
        Integer owned = jdbcClient.sql("""
                        SELECT COUNT(*) FROM media_asset
                        WHERE id = :mediaId AND uploader_id = :authorId AND upload_status = 'COMPLETED'
                        """)
                .param("mediaId", mediaId).param("authorId", authorId).query(Integer.class).single();
        if (owned == null || owned == 0) {
            throw new BusinessException(ErrorCode.UPLOAD_REJECTED, "媒体未完成上传或不属于当前作者");
        }
    }

    private void requireSubmittableMedia(Long authorId, Long draftId) {
        Long attachmentCount = jdbcClient.sql("SELECT COUNT(*) FROM post_media WHERE post_id = :draftId")
                .param("draftId", draftId)
                .query(Long.class)
                .single();
        if (attachmentCount == null || attachmentCount == 0) {
            return;
        }
        requireMediaUploadContract();
        SubmissionMediaState state = jdbcClient.sql("""
                        SELECT COUNT(*) AS attachment_count,
                               COALESCE(SUM(CASE WHEN m.id IS NOT NULL AND m.uploader_id = :authorId
                                                   AND m.upload_status = 'COMPLETED' THEN 1 ELSE 0 END), 0) AS valid_count,
                               COALESCE(SUM(CASE WHEN pm.alt_text IS NULL OR TRIM(pm.alt_text) = '' THEN 1 ELSE 0 END), 0) AS missing_alt,
                               COALESCE(SUM(CASE WHEN pm.role NOT IN ('COVER', 'INLINE') THEN 1 ELSE 0 END), 0) AS invalid_role,
                               COALESCE(SUM(CASE WHEN pm.role = 'COVER' THEN 1 ELSE 0 END), 0) AS cover_count
                        FROM post_media pm
                        LEFT JOIN media_asset m ON m.id = pm.media_id
                        WHERE pm.post_id = :draftId
                        """)
                .param("authorId", authorId)
                .param("draftId", draftId)
                .query((rs, rowNum) -> new SubmissionMediaState(
                        rs.getLong("attachment_count"), rs.getLong("valid_count"),
                        rs.getLong("missing_alt"), rs.getLong("invalid_role"), rs.getLong("cover_count")))
                .single();
        if (state.attachmentCount() != state.validCount()) {
            throw new BusinessException(ErrorCode.UPLOAD_REJECTED, "投稿包含未完成上传或不属于当前作者的媒体");
        }
        if (state.missingAlt() > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请为每个媒体填写替代文本后再投稿");
        }
        if (state.invalidRole() > 0 || state.coverCount() > 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "媒体角色无效，请重新设置后再投稿");
        }
    }

    private void requireMediaUploadContract() {
        Integer columns = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM information_schema.columns
                        WHERE table_schema = DATABASE() AND table_name = 'media_asset'
                          AND column_name IN ('uploader_id', 'upload_status')
                        """)
                .query(Integer.class)
                .single();
        if (columns == null || columns < 2) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                    "媒体上传服务尚未完成初始化，请稍后重试");
        }
    }

    private record DraftMediaRow(Long postId, DraftMediaView media) {
    }

    private record SubmissionMediaState(long attachmentCount, long validCount, long missingAlt,
                                        long invalidRole, long coverCount) {
    }

    private record AppealState(long postId, String status) {
    }

    private Long claimIdempotency(Long userId, String operation, String idempotencyKey) {
        int inserted = jdbcClient.sql("""
                        INSERT IGNORE INTO content_idempotency_record(idempotency_key, user_id, operation)
                        VALUES (:key, :userId, :operation)
                        """)
                .param("key", idempotencyKey)
                .param("userId", userId)
                .param("operation", operation)
                .update();
        if (inserted == 1) {
            return null;
        }
        IdempotencyRecord existing = jdbcClient.sql("""
                        SELECT user_id, operation, resource_id
                        FROM content_idempotency_record WHERE idempotency_key = :key
                        """)
                .param("key", idempotencyKey)
                .query((rs, rowNum) -> new IdempotencyRecord(
                        rs.getLong("user_id"), rs.getString("operation"), (Long) rs.getObject("resource_id")))
                .single();
        if (!existing.userId().equals(userId) || !existing.operation().equals(operation)) {
            throw new BusinessException(ErrorCode.DUPLICATE_OPERATION, "Idempotency-Key 已被其他写操作使用");
        }
        if (existing.resourceId() == null) {
            throw new BusinessException(ErrorCode.DUPLICATE_OPERATION, "相同请求正在处理中");
        }
        return existing.resourceId();
    }

    private void attachIdempotencyResource(String idempotencyKey, Long resourceId) {
        jdbcClient.sql("UPDATE content_idempotency_record SET resource_id = :resourceId WHERE idempotency_key = :key")
                .param("resourceId", resourceId)
                .param("key", idempotencyKey)
                .update();
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
        writeOutbox(EventTopics.POST_SUBMITTED, "POST", postId.toString(), Map.of(
                "postId", postId,
                "authorId", authorId,
                "title", request.title(),
                "tags", safeTags(request.tags()),
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

    private List<io.github.devcircle.content.api.PostMediaView> publicMediaAssets(Long postId) {
        return jdbcClient.sql("""
                        SELECT m.id, m.object_key, m.url, m.content_type, m.size_bytes,
                               m.width, m.height, m.duration_seconds, m.status,
                               pm.role, pm.alt_text, pm.sort_order
                        FROM post_media pm
                        JOIN media_asset m ON m.id = pm.media_id
                        WHERE pm.post_id = :postId AND m.status = 'APPROVED'
                        ORDER BY pm.sort_order, m.id
                        """)
                .param("postId", postId)
                .query((rs, rowNum) -> new io.github.devcircle.content.api.PostMediaView(
                        rs.getLong("id"), rs.getString("object_key"), rs.getString("url"),
                        rs.getString("content_type"), rs.getLong("size_bytes"),
                        (Integer) rs.getObject("width"), (Integer) rs.getObject("height"),
                        (Integer) rs.getObject("duration_seconds"), rs.getString("status"),
                        rs.getString("role"), rs.getString("alt_text"), rs.getInt("sort_order")
                ))
                .list();
    }

    private DeductionSnapshot deductionSnapshot(Long userId, Long postId) {
        if (userId == null || userId <= 0) {
            return DeductionSnapshot.empty();
        }
        return jdbcClient.sql("""
                        SELECT correct, revealed, points FROM deduction_attempt
                        WHERE user_id = :userId AND post_id = :postId
                        """)
                .param("userId", userId).param("postId", postId)
                .query((rs, rowNum) -> new DeductionSnapshot(true, rs.getBoolean("correct"),
                        rs.getBoolean("revealed"), rs.getInt("points")))
                .optional().orElse(DeductionSnapshot.empty());
    }

    private ReadingProgressView readingProgress(Long userId, Long postId) {
        if (userId == null || userId <= 0) {
            return new ReadingProgressView(postId, 0, null, false, null);
        }
        return jdbcClient.sql("""
                        SELECT progress_percent, anchor, completed, last_read_at FROM reading_progress
                        WHERE user_id = :userId AND post_id = :postId
                        """)
                .param("userId", userId).param("postId", postId)
                .query((rs, rowNum) -> new ReadingProgressView(postId, rs.getInt("progress_percent"),
                        rs.getString("anchor"), rs.getBoolean("completed"),
                        rs.getTimestamp("last_read_at").toInstant()))
                .optional().orElse(new ReadingProgressView(postId, 0, null, false, null));
    }

    private record DeductionSnapshot(boolean attempted, boolean solved, boolean revealed, int points) {
        static DeductionSnapshot empty() {
            return new DeductionSnapshot(false, false, false, 0);
        }
    }

    private record ProgressIdempotency(Long userId, Long postId) {
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
            @SuppressWarnings("unchecked")
            Map<String, Object> eventPayload = payload instanceof Map<?, ?> map
                    ? (Map<String, Object>) map
                    : objectMapper.convertValue(payload, Map.class);
            EventEnvelope event = EventEnvelope.now(topic, "dc-content", aggregateId, eventPayload);
            jdbcClient.sql("""
                            INSERT INTO outbox_event(event_id, topic, aggregate_type, aggregate_id, payload)
                            VALUES (:eventId, :topic, :aggregateType, :aggregateId, CAST(:payload AS JSON))
                            """)
                    .param("eventId", event.eventId().toString())
                    .param("topic", event.type())
                    .param("aggregateType", aggregateType)
                    .param("aggregateId", event.aggregateId())
                    .param("payload", objectMapper.writeValueAsString(event))
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
                            AS incoming
                            ON DUPLICATE KEY UPDATE risk_score = GREATEST(risk_score, incoming.risk_score), status = incoming.status, reason = incoming.reason
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

    private List<Long> safeMediaIds(List<Long> mediaIds) {
        return mediaIds == null ? List.of() : mediaIds;
    }

    private String excerpt(String content) {
        return content.substring(0, Math.min(140, content.length()));
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private record ModerationRisk(String queueName, String machineResult, String riskLevel, double score, String reason, String reviewLevel, int slaHours, String signalType) {
    }

    private record IdempotencyRecord(Long userId, String operation, Long resourceId) {
    }
}
