package io.github.devcircle.content.infrastructure;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.content.api.ReadingProgressRequest;
import io.github.devcircle.content.api.ReadingProgressSummary;
import io.github.devcircle.content.api.ReadingProgressView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public class ReadingProgressRepository {
    private final JdbcClient jdbcClient;

    public ReadingProgressRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
    }

    @Transactional
    public ReadingProgressView save(Long userId, Long postId, ReadingProgressRequest request,
                                    String idempotencyKey) {
        Integer published = jdbcClient.sql("SELECT COUNT(*) FROM post WHERE id = :postId AND status = 'PUBLISHED'")
                .param("postId", postId).query(Integer.class).single();
        if (published == null || published == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "内容不存在或尚未发布");
        }
        int progress = request.completed() ? 100 : Math.max(0, Math.min(request.progressPercent(), 99));
        String normalizedAnchor = request.anchor() == null || request.anchor().isBlank()
                ? null : request.anchor().trim();
        String requestFingerprint = IdempotencyFingerprint.of(
                "reading-progress-v1", progress, normalizedAnchor, request.completed());
        int claimed = jdbcClient.sql("""
                        INSERT IGNORE INTO reading_progress_idempotency(
                            idempotency_key, user_id, post_id, request_fingerprint)
                        VALUES (:key, :userId, :postId, :requestFingerprint)
                        """)
                .param("key", idempotencyKey).param("userId", userId).param("postId", postId)
                .param("requestFingerprint", requestFingerprint).update();
        if (claimed == 0) {
            ProgressIdempotency existing = jdbcClient.sql("""
                            SELECT user_id, post_id, request_fingerprint
                            FROM reading_progress_idempotency WHERE idempotency_key = :key
                            """)
                    .param("key", idempotencyKey)
                    .query((rs, rowNum) -> new ProgressIdempotency(
                            rs.getLong("user_id"), rs.getLong("post_id"), rs.getString("request_fingerprint")))
                    .single();
            boolean legacyRecord = IdempotencyFingerprint.LEGACY.equals(existing.requestFingerprint());
            if (!existing.userId().equals(userId) || !existing.postId().equals(postId)
                    || (!legacyRecord && !existing.requestFingerprint().equals(requestFingerprint))) {
                throw new BusinessException(ErrorCode.DUPLICATE_OPERATION,
                        "Idempotency-Key 已用于其他阅读进度请求或载荷");
            }
            return get(userId, postId);
        }
        jdbcClient.sql("""
                        INSERT IGNORE INTO reading_progress(user_id, post_id, progress_percent, anchor, completed)
                        VALUES (:userId, :postId, :progress, :anchor, :completed)
                        """)
                .param("userId", userId).param("postId", postId).param("progress", progress)
                .param("anchor", normalizedAnchor).param("completed", request.completed()).update();
        jdbcClient.sql("""
                        UPDATE reading_progress
                        SET anchor = CASE WHEN :progress >= progress_percent THEN :anchor ELSE anchor END,
                            progress_percent = GREATEST(progress_percent, :progress),
                            completed = completed OR :completed,
                            last_read_at = CURRENT_TIMESTAMP
                        WHERE user_id = :userId AND post_id = :postId
                        """)
                .param("userId", userId).param("postId", postId).param("progress", progress)
                .param("anchor", normalizedAnchor).param("completed", request.completed()).update();
        return get(userId, postId);
    }

    public ReadingProgressView get(Long userId, Long postId) {
        return jdbcClient.sql("""
                        SELECT post_id, progress_percent, anchor, completed, last_read_at
                        FROM reading_progress WHERE user_id = :userId AND post_id = :postId
                        """)
                .param("userId", userId).param("postId", postId)
                .query((rs, rowNum) -> new ReadingProgressView(rs.getLong("post_id"),
                        rs.getInt("progress_percent"), rs.getString("anchor"), rs.getBoolean("completed"),
                        rs.getTimestamp("last_read_at").toInstant()))
                .optional()
                .orElse(new ReadingProgressView(postId, 0, null, false, null));
    }

    public List<ReadingProgressSummary> list(Long userId, int size) {
        return jdbcClient.sql("""
                        SELECT rp.post_id,
                               CASE WHEN p.id IS NULL OR p.status <> 'PUBLISHED' THEN '内容已不可用' ELSE p.title END AS title,
                               CASE WHEN p.status = 'PUBLISHED' THEN p.theme ELSE NULL END AS theme,
                               rp.progress_percent, rp.completed, rp.last_read_at,
                               CASE WHEN p.status = 'PUBLISHED' THEN TRUE ELSE FALSE END AS available,
                               CASE
                                 WHEN p.id IS NULL OR p.status <> 'PUBLISHED' THEN 'UNAVAILABLE'
                                 WHEN COALESCE(da.revealed, FALSE) THEN 'REVEALED'
                                 WHEN COALESCE(da.correct, FALSE) THEN 'SOLVED'
                                 WHEN rp.completed THEN 'COMPLETED'
                                 ELSE 'IN_PROGRESS'
                               END AS track_state
                        FROM reading_progress rp
                        LEFT JOIN post p ON p.id = rp.post_id
                        LEFT JOIN deduction_attempt da ON da.user_id = rp.user_id AND da.post_id = rp.post_id
                        WHERE rp.user_id = :userId
                        ORDER BY CASE
                                   WHEN p.status = 'PUBLISHED' AND NOT rp.completed
                                        AND NOT COALESCE(da.correct, FALSE) AND NOT COALESCE(da.revealed, FALSE)
                                   THEN 0 ELSE 1
                                 END,
                                 rp.last_read_at DESC
                        LIMIT :size
                        """)
                .param("userId", userId).param("size", Math.max(1, Math.min(size, 50)))
                .query((rs, rowNum) -> new ReadingProgressSummary(
                        rs.getLong("post_id"), rs.getString("title"), rs.getString("theme"),
                        rs.getInt("progress_percent"), rs.getBoolean("completed"),
                        rs.getTimestamp("last_read_at").toInstant(),
                        rs.getBoolean("available"), rs.getString("track_state")))
                .list();
    }

    private record ProgressIdempotency(Long userId, Long postId, String requestFingerprint) {
    }
}
