package io.github.devcircle.content.infrastructure;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.content.api.PostAuditView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

/** Owns author appeals and the protected author-facing audit timeline. */
@Repository
public class PostAppealRepository {
    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;
    private final ContentIdempotencyStore idempotencyStore;
    private final ContentEventStore eventStore;

    public PostAppealRepository(JdbcTemplate jdbcTemplate,
                                ContentIdempotencyStore idempotencyStore,
                                ContentEventStore eventStore) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.idempotencyStore = idempotencyStore;
        this.eventStore = eventStore;
    }

    @Transactional
    public Map<String, Object> create(Long userId, Long postId, String reason, String idempotencyKey) {
        Long existingId = idempotencyStore.claim(userId, "CREATE_APPEAL:" + postId, idempotencyKey,
                IdempotencyFingerprint.of("create-appeal-v1", postId, reason));
        if (existingId != null) {
            AppealState existing = jdbcClient.sql("SELECT post_id, status FROM appeal_record WHERE id = :appealId")
                    .param("appealId", existingId)
                    .query((rs, rowNum) -> new AppealState(rs.getLong("post_id"), rs.getString("status")))
                    .optional()
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "申诉记录不存在"));
            return Map.of("appealId", existingId, "postId", existing.postId(), "status", existing.status(),
                    "duplicateRequest", true);
        }
        Long owned = jdbcClient.sql("""
                        SELECT COUNT(*) FROM post
                        WHERE id = :postId AND author_id = :userId AND status IN ('REJECTED', 'CHANGES_REQUIRED')
                        """)
                .param("postId", postId).param("userId", userId).query(Long.class).single();
        if (owned == null || owned == 0) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只能对自己被驳回或要求修改的投稿发起申诉");
        }
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO appeal_record(post_id, user_id, reason) VALUES (?, ?, ?)", new String[]{"id"});
            statement.setLong(1, postId);
            statement.setLong(2, userId);
            statement.setString(3, reason.trim());
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        Long appealId = key == null ? 0L : key.longValue();
        jdbcClient.sql("INSERT INTO post_audit_record(post_id, auditor_id, action, reason) VALUES (:postId, :userId, 'APPEAL_CREATED', :reason)")
                .param("postId", postId).param("userId", userId).param("reason", reason.trim()).update();
        eventStore.append(EventTopics.APPEAL_CREATED, "APPEAL", appealId.toString(),
                Map.of("appealId", appealId, "postId", postId, "userId", userId));
        idempotencyStore.attachResource(idempotencyKey, appealId);
        return Map.of("appealId", appealId, "postId", postId, "status", "PENDING");
    }

    public List<PostAuditView> listAudits(Long authorId, Long postId) {
        Integer owned = jdbcClient.sql("SELECT COUNT(*) FROM post WHERE id = :postId AND author_id = :authorId")
                .param("postId", postId).param("authorId", authorId).query(Integer.class).single();
        if (owned == null || owned == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "投稿不存在");
        }
        return jdbcClient.sql("""
                        SELECT id, post_id, auditor_id, action, reason, created_at
                        FROM post_audit_record WHERE post_id = :postId ORDER BY created_at ASC, id ASC
                        """)
                .param("postId", postId)
                .query((rs, rowNum) -> new PostAuditView(
                        rs.getLong("id"), rs.getLong("post_id"), (Long) rs.getObject("auditor_id"),
                        rs.getString("action"), rs.getString("reason"), rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    private record AppealState(long postId, String status) {
    }
}
