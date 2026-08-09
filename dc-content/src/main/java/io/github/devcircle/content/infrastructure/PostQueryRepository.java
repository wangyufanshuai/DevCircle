package io.github.devcircle.content.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.content.api.FavoritePostView;
import io.github.devcircle.content.api.PostCursorPage;
import io.github.devcircle.content.api.PostDetailView;
import io.github.devcircle.content.api.PostMediaView;
import io.github.devcircle.content.api.ReadingProgressView;
import io.github.devcircle.content.api.SubmissionView;
import io.github.devcircle.content.contract.MediaAssetView;
import io.github.devcircle.content.contract.PostSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class PostQueryRepository {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public PostQueryRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.objectMapper = objectMapper;
    }

    public List<PostSummary> list(int page, int size) {
        return list(page, size, null, null, null);
    }

    public List<PostSummary> list(int page, int size, String theme, String contentKind, String difficulty) {
        int offset = Math.max(page - 1, 0) * size;
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
                .query(this::mapSummary)
                .list();
        return attachMediaAssets(rows);
    }

    public long count() {
        Long count = jdbcClient.sql("SELECT COUNT(*) FROM post").query(Long.class).single();
        return count == null ? 0 : count;
    }

    public PostSummary detail(Long id) {
        PostSummary row = jdbcClient.sql("""
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
                        rs.getLong("id"), rs.getString("type"), rs.getString("theme"),
                        rs.getString("content_kind"), rs.getString("difficulty"), rs.getBoolean("solvable"),
                        rs.getLong("solved_count"), rs.getString("title"), rs.getString("excerpt"),
                        "user-" + rs.getLong("author_id"), rs.getLong("author_id"), splitTags(rs.getString("tags")),
                        List.of(), rs.getLong("like_count"), rs.getLong("comment_count"), rs.getLong("view_count"),
                        rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                        readStringList(rs.getString("clues")), readStringList(rs.getString("suspects")),
                        readStringList(rs.getString("timeline")), Optional.ofNullable(rs.getString("question")).orElse(""),
                        readStringList(rs.getString("answer_keywords")),
                        Optional.ofNullable(rs.getString("explanation")).orElse(""),
                        readStringList(rs.getString("worldbuilding")), readStringList(rs.getString("cosmic_facts")),
                        readLongList(rs.getString("related_post_ids"))))
                .single();
        return attachMediaAssets(List.of(row)).getFirst();
    }

    public PostDetailView publicDetail(Long id, Long viewerId) {
        DeductionSnapshot deduction = deductionSnapshot(viewerId, id);
        ReadingProgressView progress = readingProgress(viewerId, id);
        PostDetailView detail = jdbcClient.sql("""
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
                            rs.getBoolean("solvable"), rs.getLong("solved_count"), rs.getString("title"),
                            excerpt(markdown), markdown,
                            new PostDetailView.AuthorView(authorId, "user-" + authorId, null, null, null, null),
                            splitTags(rs.getString("tags")), List.of(), rs.getLong("like_count"),
                            rs.getLong("comment_count"), rs.getLong("view_count"), rs.getString("status"),
                            rs.getTimestamp("created_at").toInstant(), readStringList(rs.getString("clues")),
                            readStringList(rs.getString("suspects")), readStringList(rs.getString("timeline")),
                            Optional.ofNullable(rs.getString("question")).orElse(""),
                            readStringList(rs.getString("worldbuilding")), readStringList(rs.getString("cosmic_facts")),
                            readLongList(rs.getString("related_post_ids")),
                            new PostDetailView.ViewerState(false, false, progress.progressPercent(),
                                    progress.anchor(), progress.completed()),
                            new PostDetailView.DeductionState(deduction.attempted(), deduction.solved(),
                                    deduction.revealed(), deduction.points(), unlocked),
                            unlocked ? Optional.ofNullable(rs.getString("solution")).orElse("") : null);
                })
                .optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "内容不存在或尚未发布"));
        return withPublicMedia(detail, publicMediaAssets(detail.id()));
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

    public PostCursorPage listCursor(String cursor, int size, String type, String theme,
                                     String contentKind, String difficulty) {
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
                          AND (:type IS NULL OR p.type = :type)
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
                .param("type", normalizeNullableType(type))
                .param("theme", normalizeNullableTheme(theme))
                .param("contentKind", normalizeNullableContentKind(contentKind))
                .param("difficulty", normalizeNullableDifficulty(difficulty))
                .param("cursorTime", value == null ? null : java.sql.Timestamp.from(value.createdAt()))
                .param("cursorId", value == null ? Long.MAX_VALUE : value.id())
                .param("limit", size + 1)
                .query(this::mapSummary)
                .list();
        rows = attachMediaAssets(rows);
        boolean hasMore = rows.size() > size;
        List<PostSummary> page = hasMore ? rows.subList(0, size) : rows;
        String next = page.isEmpty() || !hasMore ? "" : encodeCursor(page.getLast().createdAt(), page.getLast().id());
        return new PostCursorPage(List.copyOf(page), next, hasMore);
    }

    public List<PostSummary> related(Long id, int size) {
        PostSummary current = detail(id);
        List<PostSummary> rows = jdbcClient.sql("""
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
                .query(this::mapSummary)
                .list();
        return attachMediaAssets(rows);
    }

    public List<SubmissionView> listSubmissions(Long userId, int size) {
        return jdbcClient.sql("""
                        SELECT id, title, status, updated_at, review_reason FROM post
                        WHERE author_id = :userId
                        ORDER BY updated_at DESC LIMIT :size
                        """)
                .param("userId", userId)
                .param("size", Math.max(1, Math.min(size, 50)))
                .query((rs, rowNum) -> new SubmissionView(
                        rs.getLong("id"), rs.getString("title"), rs.getString("status"),
                        rs.getTimestamp("updated_at").toInstant(), rs.getString("review_reason")))
                .list();
    }

    private PostSummary mapSummary(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new PostSummary(
                rs.getLong("id"), rs.getString("type"), rs.getString("theme"), rs.getString("content_kind"),
                rs.getString("difficulty"), rs.getBoolean("solvable"), rs.getLong("solved_count"),
                rs.getString("title"), rs.getString("excerpt"), "user-" + rs.getLong("author_id"),
                rs.getLong("author_id"), splitTags(rs.getString("tags")), List.of(), rs.getLong("like_count"),
                rs.getLong("comment_count"), rs.getLong("view_count"), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant());
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

    private List<PostSummary> attachMediaAssets(List<PostSummary> posts) {
        if (posts.isEmpty()) return posts;
        Map<Long, List<MediaAssetView>> mediaByPostId = mediaAssetsByPostId(
                posts.stream().map(PostSummary::id).distinct().toList());
        return posts.stream()
                .map(post -> withMedia(post, mediaByPostId.getOrDefault(post.id(), List.of())))
                .toList();
    }

    private Map<Long, List<MediaAssetView>> mediaAssetsByPostId(List<Long> postIds) {
        List<PostMediaAsset> rows = jdbcClient.sql("""
                        SELECT pm.post_id, m.id, m.object_key, m.url, m.content_type, m.size_bytes,
                               m.width, m.height, m.duration_seconds, m.status
                        FROM post_media pm
                        JOIN media_asset m ON m.id = pm.media_id
                        WHERE pm.post_id IN (:postIds)
                        ORDER BY pm.post_id, pm.sort_order, m.id
                        """)
                .param("postIds", postIds)
                .query((rs, rowNum) -> new PostMediaAsset(rs.getLong("post_id"), new MediaAssetView(
                        rs.getLong("id"), rs.getString("object_key"), rs.getString("url"),
                        rs.getString("content_type"), rs.getLong("size_bytes"),
                        (Integer) rs.getObject("width"), (Integer) rs.getObject("height"),
                        (Integer) rs.getObject("duration_seconds"), rs.getString("status"))))
                .list();
        Map<Long, List<MediaAssetView>> result = new LinkedHashMap<>();
        for (PostMediaAsset row : rows) {
            result.computeIfAbsent(row.postId(), ignored -> new ArrayList<>()).add(row.media());
        }
        return result;
    }

    private List<PostMediaView> publicMediaAssets(Long postId) {
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
                .query((rs, rowNum) -> new PostMediaView(
                        rs.getLong("id"), rs.getString("object_key"), rs.getString("url"),
                        rs.getString("content_type"), rs.getLong("size_bytes"),
                        (Integer) rs.getObject("width"), (Integer) rs.getObject("height"),
                        (Integer) rs.getObject("duration_seconds"), rs.getString("status"),
                        rs.getString("role"), rs.getString("alt_text"), rs.getInt("sort_order")))
                .list();
    }

    private PostSummary withMedia(PostSummary post, List<MediaAssetView> media) {
        return new PostSummary(
                post.id(), post.type(), post.theme(), post.contentKind(), post.difficulty(), post.solvable(),
                post.solvedCount(), post.title(), post.excerpt(), post.author(), post.authorId(), post.tags(), media,
                post.likes(), post.comments(), post.views(), post.status(), post.createdAt(), post.clues(),
                post.suspects(), post.timeline(), post.question(), post.answerKeywords(), post.explanation(),
                post.worldbuilding(), post.cosmicFacts(), post.relatedPostIds());
    }

    private PostDetailView withPublicMedia(PostDetailView detail, List<PostMediaView> media) {
        return new PostDetailView(
                detail.id(), detail.type(), detail.theme(), detail.contentKind(), detail.difficulty(),
                detail.solvable(), detail.solvedCount(), detail.title(), detail.excerpt(), detail.markdown(),
                detail.author(), detail.tags(), media, detail.likes(), detail.comments(), detail.views(),
                detail.status(), detail.createdAt(), detail.clues(), detail.suspects(), detail.timeline(),
                detail.question(), detail.worldbuilding(), detail.cosmicFacts(), detail.relatedPostIds(),
                detail.viewerState(), detail.deductionState(), detail.solution());
    }

    private DeductionSnapshot deductionSnapshot(Long userId, Long postId) {
        if (userId == null || userId <= 0) return DeductionSnapshot.empty();
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
        if (userId == null || userId <= 0) return new ReadingProgressView(postId, 0, null, false, null);
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

    private String normalizeNullableTheme(String theme) {
        if (theme == null || theme.isBlank()) return null;
        String normalized = theme.toUpperCase();
        return List.of("DEDUCTION", "SCI_FI", "COSMOS").contains(normalized) ? normalized : "DEDUCTION";
    }

    private String normalizeNullableType(String type) {
        if (type == null || type.isBlank()) return null;
        return switch (type.toUpperCase()) {
            case "TEXT", "IMAGE", "VIDEO" -> type.toUpperCase();
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED, "内容类型无效");
        };
    }

    private String normalizeNullableContentKind(String contentKind) {
        if (contentKind == null || contentKind.isBlank()) return null;
        String normalized = contentKind.toUpperCase();
        return List.of("CASE_FILE", "PUZZLE", "FICTION", "WORLDBUILDING", "COSMIC_ARTICLE", "OBSERVATION_LOG")
                .contains(normalized) ? normalized : "CASE_FILE";
    }

    private String normalizeNullableDifficulty(String difficulty) {
        if (difficulty == null || difficulty.isBlank()) return null;
        String normalized = difficulty.toUpperCase();
        return List.of("EASY", "MEDIUM", "HARD").contains(normalized) ? normalized : "MEDIUM";
    }

    private List<String> splitTags(String tags) {
        return tags == null || tags.isBlank() ? List.of() : Arrays.asList(tags.split(","));
    }

    private List<String> readStringList(String json) {
        try {
            if (json == null || json.isBlank()) return List.of();
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception ex) {
            return List.of();
        }
    }

    private List<Long> readLongList(String json) {
        try {
            if (json == null || json.isBlank()) return List.of();
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Long.class));
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String excerpt(String content) {
        return content.substring(0, Math.min(140, content.length()));
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private record CursorValue(Instant createdAt, Long id) {
    }

    private record PostMediaAsset(Long postId, MediaAssetView media) {
    }

    private record DeductionSnapshot(boolean attempted, boolean solved, boolean revealed, int points) {
        static DeductionSnapshot empty() {
            return new DeductionSnapshot(false, false, false, 0);
        }
    }
}
