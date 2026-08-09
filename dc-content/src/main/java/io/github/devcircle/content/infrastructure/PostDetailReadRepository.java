package io.github.devcircle.content.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.content.api.PostDetailView;
import io.github.devcircle.content.api.ReadingProgressView;
import io.github.devcircle.content.contract.PostSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Owns detail-shaped post reads and keeps viewer state separate from list queries. */
@Repository
public class PostDetailReadRepository {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final PostMediaReadRepository mediaReadRepository;
    private final PostViewerStateReadRepository viewerStateReadRepository;

    public PostDetailReadRepository(JdbcTemplate jdbcTemplate,
                                    ObjectMapper objectMapper,
                                    PostMediaReadRepository mediaReadRepository,
                                    PostViewerStateReadRepository viewerStateReadRepository) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.objectMapper = objectMapper;
        this.mediaReadRepository = mediaReadRepository;
        this.viewerStateReadRepository = viewerStateReadRepository;
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
                        readStringList(rs.getString("answer_keywords")), Optional.ofNullable(rs.getString("explanation")).orElse(""),
                        readStringList(rs.getString("worldbuilding")), readStringList(rs.getString("cosmic_facts")),
                        readLongList(rs.getString("related_post_ids"))))
                .single();
        return mediaReadRepository.attachMediaAssets(List.of(row)).getFirst();
    }

    public PostDetailView publicDetail(Long id, Long viewerId) {
        PostViewerStateReadRepository.ViewerState viewerState = viewerStateReadRepository.read(viewerId, id);
        PostViewerStateReadRepository.DeductionSnapshot deduction = viewerState.deduction();
        ReadingProgressView progress = viewerState.progress();
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
                            rs.getString("content_kind"), rs.getString("difficulty"), rs.getBoolean("solvable"),
                            rs.getLong("solved_count"), rs.getString("title"), excerpt(markdown), markdown,
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
        return mediaReadRepository.withPublicMedia(detail);
    }

    private List<String> splitTags(String tags) {
        return tags == null || tags.isBlank() ? List.of() : Arrays.asList(tags.split(","));
    }

    private List<String> readStringList(String json) {
        try {
            if (json == null || json.isBlank()) return List.of();
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception ex) {
            return List.of();
        }
    }

    private List<Long> readLongList(String json) {
        try {
            if (json == null || json.isBlank()) return List.of();
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, Long.class));
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String excerpt(String content) {
        return content.substring(0, Math.min(140, content.length()));
    }
}
