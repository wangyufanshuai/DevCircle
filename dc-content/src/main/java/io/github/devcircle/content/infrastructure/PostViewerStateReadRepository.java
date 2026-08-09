package io.github.devcircle.content.infrastructure;

import io.github.devcircle.content.api.ReadingProgressView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads viewer-specific state needed to build a public post detail. */
@Repository
public class PostViewerStateReadRepository {
    private final JdbcClient jdbcClient;

    public PostViewerStateReadRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    public ViewerState read(Long userId, Long postId) {
        if (userId == null || userId <= 0) return ViewerState.empty(postId);
        return new ViewerState(readDeduction(userId, postId), readProgress(userId, postId));
    }

    private DeductionSnapshot readDeduction(Long userId, Long postId) {
        return jdbcClient.sql("""
                        SELECT correct, revealed, points FROM deduction_attempt
                        WHERE user_id = :userId AND post_id = :postId
                        """)
                .param("userId", userId).param("postId", postId)
                .query((rs, rowNum) -> new DeductionSnapshot(true, rs.getBoolean("correct"),
                        rs.getBoolean("revealed"), rs.getInt("points")))
                .optional().orElse(DeductionSnapshot.empty());
    }

    private ReadingProgressView readProgress(Long userId, Long postId) {
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

    public record ViewerState(DeductionSnapshot deduction, ReadingProgressView progress) {
        static ViewerState empty(Long postId) {
            return new ViewerState(DeductionSnapshot.empty(),
                    new ReadingProgressView(postId, 0, null, false, null));
        }
    }

    public record DeductionSnapshot(boolean attempted, boolean solved, boolean revealed, int points) {
        static DeductionSnapshot empty() {
            return new DeductionSnapshot(false, false, false, 0);
        }
    }
}
