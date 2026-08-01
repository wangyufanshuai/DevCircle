package io.github.devcircle.content.infrastructure;

import io.github.devcircle.common.event.EventTopics;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ContentCommentProjectionRepository {
    private final JdbcClient jdbcClient;

    public ContentCommentProjectionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    public boolean apply(String eventId, String topic, Long postId, String changeType) {
        int claimed = jdbcClient.sql("""
                        INSERT IGNORE INTO comment_projection_dedup(event_id, topic)
                        VALUES (:eventId, :topic)
                        """)
                .param("eventId", eventId).param("topic", topic).update();
        if (claimed == 0) return false;

        int delta;
        if ((EventTopics.COMMENT_CREATED.equals(topic) || EventTopics.COMMENT_CHANGED.equals(topic))
                && "CREATED".equals(changeType)) {
            delta = 1;
        } else if (EventTopics.COMMENT_CHANGED.equals(topic)
                && ("DELETED".equals(changeType) || "HIDDEN".equals(changeType))) {
            delta = -1;
        } else {
            throw new IllegalArgumentException("Unsupported comment event: " + topic + "/" + changeType);
        }
        int updated = jdbcClient.sql("""
                        UPDATE post
                        SET comment_count = GREATEST(comment_count + :delta, 0)
                        WHERE id = :postId
                        """)
                .param("delta", delta).param("postId", postId).update();
        if (updated != 1) {
            throw new IllegalStateException("Comment event references missing post " + postId);
        }
        return true;
    }
}
