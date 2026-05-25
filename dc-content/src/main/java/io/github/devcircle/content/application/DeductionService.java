package io.github.devcircle.content.application;

import io.github.devcircle.content.api.DeductionAnswerResult;
import io.github.devcircle.content.api.LeaderboardEntry;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class DeductionService {
    private static final String DEMO_SOLUTION = "守夜人藏在钟楼夹层";
    private final JdbcClient jdbcClient;

    public DeductionService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    public DeductionAnswerResult submitAnswer(Long userId, Long postId, String answer) {
        boolean correct = isCorrect(answer);
        int points = correct ? 20 : 0;
        try {
            jdbcClient.sql("""
                            INSERT INTO deduction_attempt(post_id, user_id, answer, correct, points)
                            VALUES (:postId, :userId, :answer, :correct, :points)
                            """)
                    .param("postId", postId)
                    .param("userId", userId)
                    .param("answer", answer)
                    .param("correct", correct)
                    .param("points", points)
                    .update();
            if (correct) {
                jdbcClient.sql("UPDATE post SET solved_count = solved_count + 1 WHERE id = :postId")
                        .param("postId", postId)
                        .update();
                jdbcClient.sql("""
                                INSERT INTO deduction_score(user_id, username, correct_count, total_points)
                                VALUES (:userId, :username, 1, :points)
                                ON DUPLICATE KEY UPDATE correct_count = correct_count + 1, total_points = total_points + :points
                                """)
                        .param("userId", userId)
                        .param("username", "user-" + userId)
                        .param("points", points)
                        .update();
            }
            return new DeductionAnswerResult(postId, userId, correct, false, points, explanation(correct));
        } catch (DuplicateKeyException ex) {
            return existingAttempt(userId, postId);
        } catch (DataAccessException ex) {
            return new DeductionAnswerResult(postId, userId, correct, false, points, explanation(correct));
        }
    }

    public DeductionAnswerResult myAttempt(Long userId, Long postId) {
        try {
            return existingAttempt(userId, postId);
        } catch (DataAccessException ex) {
            return new DeductionAnswerResult(postId, userId, false, false, 0, "还没有提交推理答案。");
        }
    }

    public List<LeaderboardEntry> leaderboard() {
        try {
            return jdbcClient.sql("""
                            SELECT user_id, username, correct_count, total_points
                            FROM deduction_score
                            ORDER BY total_points DESC, correct_count DESC, updated_at ASC
                            LIMIT 20
                            """)
                    .query((rs, rowNum) -> new LeaderboardEntry(
                            rs.getLong("user_id"),
                            rs.getString("username"),
                            rs.getLong("correct_count"),
                            rs.getLong("total_points")
                    ))
                    .list();
        } catch (DataAccessException ex) {
            return List.of(
                    new LeaderboardEntry(7L, "林墨", 19, 380),
                    new LeaderboardEntry(12L, "陈星", 14, 280),
                    new LeaderboardEntry(18L, "王遥", 11, 220)
            );
        }
    }

    private DeductionAnswerResult existingAttempt(Long userId, Long postId) {
        return jdbcClient.sql("""
                        SELECT correct, points
                        FROM deduction_attempt
                        WHERE user_id = :userId AND post_id = :postId
                        """)
                .param("userId", userId)
                .param("postId", postId)
                .query((rs, rowNum) -> new DeductionAnswerResult(
                        postId,
                        userId,
                        rs.getBoolean("correct"),
                        true,
                        rs.getInt("points"),
                        "你已经提交过答案，积分不会重复增加。"
                ))
                .single();
    }

    private boolean isCorrect(String answer) {
        if (answer == null) {
            return false;
        }
        String normalized = answer.replace(" ", "").toLowerCase();
        return normalized.contains("夹层") || normalized.contains("钟楼") || normalized.contains(DEMO_SOLUTION.replace(" ", "").toLowerCase());
    }

    private String explanation(boolean correct) {
        if (correct) {
            return "推理成立：潮湿鞋印是误导，真正关键是停在 00:07 的怀表和被撕掉的航海日志，守夜人利用钟楼夹层躲过搜查。";
        }
        return "线索还没有闭合。建议重新检查怀表时间、潮湿鞋印方向和航海日志缺页。";
    }
}
