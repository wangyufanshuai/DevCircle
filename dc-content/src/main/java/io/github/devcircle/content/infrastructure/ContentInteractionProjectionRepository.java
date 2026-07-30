package io.github.devcircle.content.infrastructure;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ContentInteractionProjectionRepository {
    private final JdbcClient jdbcClient;

    public ContentInteractionProjectionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    public boolean apply(String eventId, String topic, Long postId, String interactionType) {
        int claimed = jdbcClient.sql("""
                        INSERT IGNORE INTO interaction_projection_dedup(event_id, topic)
                        VALUES (:eventId, :topic)
                        """)
                .param("eventId", eventId)
                .param("topic", topic)
                .update();
        if (claimed == 0) {
            return false;
        }

        int delta = switch (interactionType) {
            case "LIKE" -> 1;
            case "UNLIKE" -> -1;
            case "FAVORITE", "UNFAVORITE" -> 0;
            default -> throw new IllegalArgumentException("Unsupported interaction type: " + interactionType);
        };
        if (delta == 0) {
            return true;
        }
        int updated = jdbcClient.sql("""
                        UPDATE post
                        SET like_count = GREATEST(like_count + :delta, 0)
                        WHERE id = :postId
                        """)
                .param("delta", delta)
                .param("postId", postId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Interaction event references missing post " + postId);
        }
        return true;
    }
}
