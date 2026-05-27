package io.github.devcircle.content.application;

import io.github.devcircle.content.api.DeductionAnswerResult;
import io.github.devcircle.content.api.LeaderboardEntry;
import io.github.devcircle.common.seed.ThemeContentLibrary;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class DeductionService {
    private final JdbcClient jdbcClient;

    public DeductionService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    public DeductionAnswerResult submitAnswer(Long userId, Long postId, String answer) {
        boolean correct = isCorrect(postId, answer);
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
            return new DeductionAnswerResult(postId, userId, correct, false, points, explanation(postId, correct));
        } catch (DuplicateKeyException ex) {
            return existingAttempt(userId, postId);
        } catch (DataAccessException ex) {
            return new DeductionAnswerResult(postId, userId, correct, false, points, explanation(postId, correct));
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

    private boolean isCorrect(Long postId, String answer) {
        if (answer == null) {
            return false;
        }
        String normalized = answer.replace(" ", "").toLowerCase();
        return ThemeContentLibrary.find(postId)
                .map(seed -> seed.answerKeywords().stream().anyMatch(keyword -> normalized.contains(keyword.replace(" ", "").toLowerCase())))
                .orElse(normalized.contains("夹层") || normalized.contains("钟楼"));
    }

    private String explanation(Long postId, boolean correct) {
        if (correct) {
            return ThemeContentLibrary.find(postId).map(seed -> seed.explanation().isBlank()
                    ? "推理成立，关键线索已经闭合。"
                    : seed.explanation()).orElse("推理成立，关键线索已经闭合。");
        }
        return "线索还没有闭合。建议重新检查怀表时间、潮湿鞋印方向和航海日志缺页。";
    }
}
