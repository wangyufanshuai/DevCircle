package io.github.devcircle.content.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.content.api.DeductionAnswerResult;
import io.github.devcircle.content.api.DeductionStatsView;
import io.github.devcircle.content.api.LeaderboardEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

@Service
public class DeductionService {
    private static final String LEGACY_REQUEST_FINGERPRINT = "0".repeat(64);
    private final JdbcClient jdbcClient;
    private final AnswerKeywordPolicy answerKeywordPolicy;

    @Autowired
    public DeductionService(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this(jdbcClient, new AnswerKeywordPolicy(objectMapper));
    }

    /** Compatibility constructor retained for focused unit tests and legacy embedders. */
    public DeductionService(JdbcClient jdbcClient) {
        this(jdbcClient, new AnswerKeywordPolicy(new ObjectMapper()));
    }

    DeductionService(JdbcClient jdbcClient, AnswerKeywordPolicy answerKeywordPolicy) {
        this.jdbcClient = jdbcClient;
        this.answerKeywordPolicy = answerKeywordPolicy;
    }

    // A duplicate-key wait may observe a commit after the initial availability read.
    // READ_COMMITTED lets the replay read that winning attempt instead of an older snapshot.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DeductionAnswerResult submitAnswer(Long userId, Long postId, String answer, String idempotencyKey) {
        requireRequest(userId, postId, idempotencyKey);
        requireAnswer(answer);
        requirePublishedCase(postId);
        String requestFingerprint = requestFingerprint("deduction-answer-v1", answerKeywordPolicy.normalize(answer));
        if (!claim(userId, postId, "ANSWER", idempotencyKey, requestFingerprint)) {
            return existingAttempt(userId, postId, true);
        }
        boolean correct = isCorrect(postId, answer);
        int points = correct ? 20 : 0;
        try {
            jdbcClient.sql("""
                            INSERT INTO deduction_attempt(post_id, user_id, answer, correct, revealed, points)
                            VALUES (:postId, :userId, :answer, :correct, FALSE, :points)
                            """)
                    .param("postId", postId).param("userId", userId).param("answer", answer)
                    .param("correct", correct).param("points", points).update();
            if (correct) {
                jdbcClient.sql("UPDATE post SET solved_count = solved_count + 1 WHERE id = :postId")
                        .param("postId", postId).update();
                jdbcClient.sql("""
                                INSERT INTO deduction_score(user_id, username, correct_count, total_points)
                                VALUES (:userId, :username, 1, :points)
                                ON DUPLICATE KEY UPDATE correct_count = correct_count + 1,
                                                        total_points = total_points + :points
                                """)
                        .param("userId", userId).param("username", "user-" + userId)
                        .param("points", points).update();
            }
            return result(postId, userId, correct, false, false, points);
        } catch (DuplicateKeyException ex) {
            return existingAttempt(userId, postId, true);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DeductionAnswerResult reveal(Long userId, Long postId, String idempotencyKey) {
        requireRequest(userId, postId, idempotencyKey);
        requirePublishedCase(postId);
        if (!claim(userId, postId, "REVEAL", idempotencyKey,
                requestFingerprint("deduction-reveal-v1", postId))) {
            return existingAttempt(userId, postId, true);
        }
        int updated = jdbcClient.sql("""
                        UPDATE deduction_attempt SET revealed = TRUE, points = 0, revealed_at = CURRENT_TIMESTAMP
                        WHERE user_id = :userId AND post_id = :postId AND correct = FALSE
                        """)
                .param("userId", userId).param("postId", postId).update();
        if (updated == 0) {
            try {
                jdbcClient.sql("""
                                INSERT INTO deduction_attempt(post_id, user_id, answer, correct, revealed, points, revealed_at)
                                VALUES (:postId, :userId, '[REVEALED]', FALSE, TRUE, 0, CURRENT_TIMESTAMP)
                                """)
                        .param("postId", postId).param("userId", userId).update();
            } catch (DuplicateKeyException ignored) {
                // A concurrent wrong answer may have won the unique constraint after the first
                // update saw no row. Apply the reveal once more so the requested state is not lost.
                jdbcClient.sql("""
                                UPDATE deduction_attempt SET revealed = TRUE, points = 0, revealed_at = CURRENT_TIMESTAMP
                                WHERE user_id = :userId AND post_id = :postId AND correct = FALSE
                                """)
                        .param("userId", userId).param("postId", postId).update();
            }
        }
        return existingAttempt(userId, postId, false);
    }

    public DeductionAnswerResult myAttempt(Long userId, Long postId) {
        requireUser(userId);
        requirePostId(postId);
        requirePublishedCase(postId);
        Integer count = jdbcClient.sql("""
                        SELECT COUNT(*) FROM deduction_attempt WHERE user_id = :userId AND post_id = :postId
                        """)
                .param("userId", userId).param("postId", postId).query(Integer.class).single();
        if (count == null || count == 0) {
            return new DeductionAnswerResult(postId, userId, false, false, false, false,
                    0, "还没有提交推理答案。", null);
        }
        return existingAttempt(userId, postId, false);
    }

    public List<LeaderboardEntry> leaderboard() {
        return jdbcClient.sql("""
                        SELECT user_id, username, correct_count, total_points
                        FROM deduction_score
                        ORDER BY total_points DESC, correct_count DESC, updated_at ASC
                        LIMIT 20
                        """)
                .query((rs, rowNum) -> new LeaderboardEntry(rs.getLong("user_id"), rs.getString("username"),
                        rs.getLong("correct_count"), rs.getLong("total_points")))
                .list();
    }

    public DeductionStatsView stats(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
        return jdbcClient.sql("""
                        SELECT COUNT(*) AS attempts,
                               COALESCE(SUM(CASE WHEN correct = TRUE THEN 1 ELSE 0 END), 0) AS correct_count,
                               COALESCE(SUM(CASE WHEN revealed = TRUE THEN 1 ELSE 0 END), 0) AS revealed_count,
                               COALESCE(SUM(points), 0) AS total_points
                        FROM deduction_attempt WHERE user_id = :userId
                        """)
                .param("userId", userId)
                .query((rs, rowNum) -> new DeductionStatsView(rs.getLong("attempts"),
                        rs.getLong("correct_count"), rs.getLong("revealed_count"), rs.getLong("total_points")))
                .single();
    }

    private DeductionAnswerResult existingAttempt(Long userId, Long postId, boolean duplicated) {
        return jdbcClient.sql("""
                        SELECT correct, revealed, points FROM deduction_attempt
                        WHERE user_id = :userId AND post_id = :postId
                        """)
                .param("userId", userId).param("postId", postId)
                .query((rs, rowNum) -> result(postId, userId, rs.getBoolean("correct"), duplicated,
                        rs.getBoolean("revealed"), rs.getInt("points")))
                .single();
    }

    private DeductionAnswerResult result(Long postId, Long userId, boolean correct, boolean duplicated,
                                         boolean revealed, int points) {
        boolean unlocked = correct || revealed;
        String message = duplicated ? "该请求已经处理，积分不会重复增加。" : explanation(postId, correct, revealed);
        return new DeductionAnswerResult(postId, userId, correct, duplicated, revealed, unlocked, points,
                message, unlocked ? solution(postId) : null);
    }

    private boolean claim(Long userId, Long postId, String operation, String key, String requestFingerprint) {
        int inserted = jdbcClient.sql("""
                        INSERT IGNORE INTO deduction_idempotency(
                            idempotency_key, user_id, post_id, operation, request_fingerprint)
                        VALUES (:key, :userId, :postId, :operation, :requestFingerprint)
                        """)
                .param("key", key).param("userId", userId).param("postId", postId)
                .param("operation", operation).param("requestFingerprint", requestFingerprint).update();
        if (inserted == 1) return true;
        Idempotency existing = jdbcClient.sql("""
                        SELECT user_id, post_id, operation, request_fingerprint
                        FROM deduction_idempotency WHERE idempotency_key = :key
                        """)
                .param("key", key)
                .query((rs, rowNum) -> new Idempotency(rs.getLong("user_id"), rs.getLong("post_id"),
                        rs.getString("operation"), rs.getString("request_fingerprint"))).single();
        boolean legacyRecord = LEGACY_REQUEST_FINGERPRINT.equals(existing.requestFingerprint());
        if (!existing.userId().equals(userId) || !existing.postId().equals(postId)
                || !existing.operation().equals(operation)
                || (!legacyRecord && !existing.requestFingerprint().equals(requestFingerprint))) {
            throw new BusinessException(ErrorCode.DUPLICATE_OPERATION,
                    "Idempotency-Key 已用于其他解谜请求或答案载荷");
        }
        return false;
    }

    private String requestFingerprint(Object... values) {
        StringBuilder canonical = new StringBuilder();
        for (Object value : values) {
            if (value == null) {
                canonical.append("-1:|");
            } else {
                String text = String.valueOf(value);
                canonical.append(text.length()).append(':').append(text).append('|');
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private void requireRequest(Long userId, Long postId, String key) {
        requireUser(userId);
        requirePostId(postId);
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "写操作必须提供有效的 Idempotency-Key");
        }
    }

    private void requireUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }
    }

    private void requirePostId(Long postId) {
        if (postId == null || postId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "内容标识无效");
        }
    }

    private void requireAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "推理答案不能为空");
        }
    }

    private void requirePublishedCase(Long postId) {
        boolean available = jdbcClient.sql("""
                        SELECT EXISTS(
                            SELECT 1 FROM post p
                            INNER JOIN post_content pc ON pc.post_id = p.id
                            WHERE p.id = :postId AND p.status = 'PUBLISHED' AND p.solvable = TRUE
                        )
                        """)
                .param("postId", postId)
                .query(Boolean.class)
                .single();
        if (!available) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "内容不存在、尚未发布或不可解谜");
        }
    }

    private boolean isCorrect(Long postId, String answer) {
        return answerKeywordPolicy.matches(answer, answerKeywords(postId));
    }

    private List<String> answerKeywords(Long postId) {
        String json = jdbcClient.sql("""
                        SELECT COALESCE(answer_keywords, '[]')
                        FROM post_content WHERE post_id = :postId
                        """)
                .param("postId", postId)
                .query(String.class)
                .single();
        return answerKeywordPolicy.parse(json);
    }

    private String explanation(Long postId, boolean correct, boolean revealed) {
        if (revealed) return "答案已经揭示，本题不会再获得积分。";
        return correct ? "推理成立，关键线索已经闭合。"
                : "线索还没有闭合。建议重新检查时间线空档、证词避开的地点和物证记录之间的矛盾。";
    }

    private String solution(Long postId) {
        return jdbcClient.sql("SELECT solution FROM post_content WHERE post_id = :postId")
                .param("postId", postId).query(String.class).optional().orElse("");
    }

    private record Idempotency(Long userId, Long postId, String operation, String requestFingerprint) {
    }
}
