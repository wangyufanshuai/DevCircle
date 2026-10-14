package io.github.devcircle.content.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.content.api.FavoritePostView;
import io.github.devcircle.content.api.PostCursorPage;
import io.github.devcircle.content.api.PostDetailView;
import io.github.devcircle.content.api.SubmissionView;
import io.github.devcircle.content.contract.PostSummary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Repository
public class PostQueryRepository {
    private final JdbcClient jdbcClient;
    private final PostMediaReadRepository mediaReadRepository;
    private final PostDetailReadRepository detailReadRepository;

    public PostQueryRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        PostMediaReadRepository media = new PostMediaReadRepository(jdbcTemplate);
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.mediaReadRepository = media;
        this.detailReadRepository = new PostDetailReadRepository(jdbcTemplate, objectMapper, media,
                new PostViewerStateReadRepository(jdbcTemplate));
    }

    @Autowired
    public PostQueryRepository(JdbcTemplate jdbcTemplate,
                               PostMediaReadRepository mediaReadRepository,
                               PostDetailReadRepository detailReadRepository) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.mediaReadRepository = mediaReadRepository;
        this.detailReadRepository = detailReadRepository;
    }

    public List<PostSummary> list(int page, int size) {
        return list(page, size, null, null, null);
    }

    public List<PostSummary> list(int page, int size, String theme, String contentKind, String difficulty) {
        int offset = Math.max(page - 1, 0) * size;
        List<PostSummary> rows = jdbcClient.sql("""
                        SELECT p.id, p.type, p.theme, p.content_kind, p.difficulty, p.solvable, p.solved_count,
                               p.title, LEFT(pc.markdown, 140) AS excerpt, p.author_id, p.status,
                               p.like_count, p.comment_count, p.view_count, p.version, p.created_at,
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
                                 p.title, pc.markdown, p.author_id, p.status, p.like_count, p.comment_count, p.view_count, p.version, p.created_at
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
        return mediaReadRepository.attachMediaAssets(rows);
    }

    public long count() {
        Long count = jdbcClient.sql("SELECT COUNT(*) FROM post").query(Long.class).single();
        return count == null ? 0 : count;
    }

    public PostSummary detail(Long id) {
        return detailReadRepository.detail(id);
    }

    public PostDetailView publicDetail(Long id, Long viewerId) {
        return detailReadRepository.publicDetail(id, viewerId);
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
                               p.like_count, p.comment_count, p.view_count, p.version, p.created_at,
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
                                 p.view_count, p.version, p.created_at
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
        rows = mediaReadRepository.attachMediaAssets(rows);
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
                               p.like_count, p.comment_count, p.view_count, p.version, p.created_at,
                               GROUP_CONCAT(t.name ORDER BY t.name SEPARATOR ',') AS tags
                        FROM post p
                        JOIN post_content pc ON pc.post_id = p.id
                        LEFT JOIN post_tag pt ON pt.post_id = p.id
                        LEFT JOIN tag t ON t.id = pt.tag_id
                        WHERE p.id <> :id AND p.theme = :theme AND p.status = 'PUBLISHED'
                        GROUP BY p.id, p.type, p.theme, p.content_kind, p.difficulty, p.solvable, p.solved_count,
                                 p.title, pc.markdown, p.author_id, p.status, p.like_count, p.comment_count, p.view_count, p.version, p.created_at
                        ORDER BY p.like_count DESC, p.created_at DESC
                        LIMIT :size
                        """)
                .param("id", id)
                .param("theme", current.theme())
                .param("size", Math.max(1, Math.min(size, 20)))
                .query(this::mapSummary)
                .list();
        return mediaReadRepository.attachMediaAssets(rows);
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
                rs.getTimestamp("created_at").toInstant()).withAggregateVersion(rs.getLong("version"));
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

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private record CursorValue(Instant createdAt, Long id) {
    }

}
