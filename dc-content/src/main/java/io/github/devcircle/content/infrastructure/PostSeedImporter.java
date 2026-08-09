package io.github.devcircle.content.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.seed.ThemeContentLibrary;
import io.github.devcircle.seed.ThemeSeedPost;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Owns the explicit first-install seed import path; production reads never depend on it. */
@Repository
public class PostSeedImporter {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final PostTagStore postTagStore;

    public PostSeedImporter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, PostTagStore postTagStore) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.objectMapper = objectMapper;
        this.postTagStore = postTagStore;
    }

    @Transactional
    public int importIfEmpty(List<ThemeSeedPost> seeds) {
        int imported = 0;
        for (ThemeSeedPost seed : seeds) {
            Long existing = jdbcClient.sql("SELECT COUNT(*) FROM post WHERE id = :id")
                    .param("id", seed.id()).query(Long.class).single();
            if (existing != null && existing > 0) continue;
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

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize structured seed content", ex);
        }
    }
}
