package io.github.devcircle.content.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.content.api.DraftView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/** Reads author drafts and assembles their media without owning draft mutations. */
@Repository
public class DraftReadRepository {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final DraftMediaRepository draftMediaRepository;

    public DraftReadRepository(JdbcTemplate jdbcTemplate,
                               ObjectMapper objectMapper,
                               DraftMediaRepository draftMediaRepository) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.objectMapper = objectMapper;
        this.draftMediaRepository = draftMediaRepository;
    }

    public List<DraftView> list(Long authorId, int size) {
        List<DraftView> drafts = query("WHERE p.author_id = :authorId")
                .param("authorId", authorId).param("size", size).query(this::mapDraft).list();
        return draftMediaRepository.attach(drafts);
    }

    public DraftView get(Long authorId, Long draftId) {
        List<DraftView> drafts = query("WHERE p.author_id = :authorId AND p.id = :draftId")
                .param("authorId", authorId).param("draftId", draftId).param("size", 1)
                .query(this::mapDraft).list();
        if (drafts.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "草稿不存在");
        return draftMediaRepository.attach(drafts).getFirst();
    }

    private JdbcClient.StatementSpec query(String whereClause) {
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
                rs.getLong("id"), rs.getLong("author_id"), safeText(rs.getString("title")),
                safeText(rs.getString("markdown")), splitTags(rs.getString("tags")), rs.getString("type"),
                readLongCsv(rs.getString("media_ids")), List.of(), rs.getString("theme"),
                rs.getString("content_kind"), rs.getString("difficulty"), rs.getBoolean("solvable"),
                String.join("\n", readStringList(rs.getString("clues"))),
                String.join("\n", readStringList(rs.getString("suspects"))),
                String.join("\n", readStringList(rs.getString("timeline"))),
                safeText(rs.getString("question")), String.join("\n", readStringList(rs.getString("answer_keywords"))),
                safeText(rs.getString("solution")), rs.getString("status"), rs.getLong("version"),
                rs.getTimestamp("last_saved_at") == null ? null : rs.getTimestamp("last_saved_at").toInstant(),
                rs.getTimestamp("submitted_at") == null ? null : rs.getTimestamp("submitted_at").toInstant(),
                rs.getString("review_reason"));
    }

    private List<String> readStringList(String json) {
        try {
            if (json == null || json.isBlank()) return List.of();
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception ex) {
            return List.of();
        }
    }

    private List<String> splitTags(String tags) {
        return tags == null || tags.isBlank() ? List.of() : Arrays.asList(tags.split(","));
    }

    private List<Long> readLongCsv(String values) {
        if (values == null || values.isBlank()) return List.of();
        return Arrays.stream(values.split(",")).map(String::trim).filter(value -> !value.isBlank())
                .map(Long::valueOf).toList();
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }
}
