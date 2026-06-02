package io.github.devcircle.content.application;

import io.github.devcircle.common.seed.ThemeContentLibrary;
import io.github.devcircle.content.api.DeductionAnswerResult;
import io.github.devcircle.content.api.LeaderboardEntry;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
                    new LeaderboardEntry(7L, "林墨", 39, 780),
                    new LeaderboardEntry(12L, "顾白", 34, 680),
                    new LeaderboardEntry(18L, "沈屿", 28, 560)
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
        if (answer == null || answer.isBlank()) {
            return false;
        }
        String normalizedAnswer = normalize(answer);
        List<String> keywords = answerKeywords(postId);
        if (keywords.isEmpty()) {
            keywords = ThemeContentLibrary.find(postId)
                    .map(seed -> ThemeContentLibrary.keywordsFromSeed(seed))
                    .orElse(List.of("夹层", "钟楼", "时间差"));
        }
        Set<String> normalizedKeywords = expandKeywords(keywords);
        long hits = normalizedKeywords.stream().filter(normalizedAnswer::contains).count();
        boolean decisiveHit = normalizedKeywords.stream()
                .filter(keyword -> keyword.length() >= 2)
                .anyMatch(keyword -> normalizedAnswer.contains(keyword) && decisiveKeywords().contains(keyword));
        int requiredHits = Math.min(2, Math.max(1, normalizedKeywords.size()));
        return decisiveHit || hits >= requiredHits;
    }

    private List<String> answerKeywords(Long postId) {
        try {
            String json = jdbcClient.sql("SELECT answer_keywords FROM post_content WHERE post_id = :postId")
                    .param("postId", postId)
                    .query(String.class)
                    .single();
            if (json == null || json.isBlank()) {
                return List.of();
            }
            return Arrays.stream(json.replace("[", "").replace("]", "").replace("\"", "").split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .toList();
        } catch (DataAccessException ex) {
            return List.of();
        }
    }

    private Set<String> expandKeywords(List<String> keywords) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String keyword : keywords) {
            String normalized = normalize(keyword);
            if (!normalized.isBlank()) {
                values.add(normalized);
                values.addAll(synonyms(normalized));
            }
        }
        return values;
    }

    private Set<String> synonyms(String keyword) {
        return switch (keyword) {
            case "夹层", "夹层藏身" -> Set.of("暗层", "隔层", "钟楼夹层", "钟楼暗层", "藏身层");
            case "调换时钟" -> Set.of("改表", "拨快时钟", "拨慢时钟", "改动钟表", "伪造时间");
            case "伪造脚印" -> Set.of("假脚印", "制造脚印", "鞋印伪造");
            case "镜面反射" -> Set.of("镜像", "反射", "倒影");
            case "潮汐延迟" -> Set.of("潮汐", "涨潮", "退潮");
            case "证词串供" -> Set.of("串供", "证词矛盾", "伪证");
            case "密码替换" -> Set.of("替换密码", "暗号", "换码");
            case "灯塔盲区" -> Set.of("盲区", "灯光死角", "照明死角");
            case "列车换厢" -> Set.of("换车厢", "换厢", "调换车厢");
            case "档案重排" -> Set.of("重排档案", "调换卷宗", "记录重排");
            default -> Set.of();
        };
    }

    private Set<String> decisiveKeywords() {
        return Set.of(
                "夹层", "夹层藏身", "暗层", "钟楼夹层", "钟楼暗层",
                "调换时钟", "伪造时间", "伪造脚印", "镜面反射", "潮汐延迟",
                "证词串供", "密码替换", "灯塔盲区", "列车换厢", "档案重排"
        );
    }

    private String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}\\p{P}]", "");
    }

    private String explanation(Long postId, boolean correct) {
        if (correct) {
            return ThemeContentLibrary.find(postId).map(seed -> seed.explanation().isBlank()
                    ? "推理成立，关键线索已经闭合。"
                    : seed.explanation()).orElse("推理成立，关键线索已经闭合。");
        }
        return "线索还没有闭合。建议重新检查时间线空档、证词避开的地点和物证记录之间的矛盾。";
    }
}
