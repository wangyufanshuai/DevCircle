package io.github.devcircle.recommend.application;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.stream.Collectors;

@Repository
class JdbcFollowSignalStore implements FollowSignalStore {
    private final JdbcClient jdbcClient;

    JdbcFollowSignalStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional
    public void apply(String eventId, long viewerId, long authorId, String status) {
        if (viewerId <= 0 || authorId <= 0 || viewerId == authorId) {
            throw new IllegalArgumentException("Invalid follow projection identity");
        }
        if (!"FOLLOWED".equals(status) && !"UNFOLLOWED".equals(status)) {
            throw new IllegalArgumentException("Unsupported follow status");
        }
        jdbcClient.sql("""
                        INSERT INTO recommend_follow_projection(viewer_id, author_id, followed, source_event_id)
                        VALUES (:viewerId, :authorId, :followed, :eventId)
                        ON DUPLICATE KEY UPDATE
                            followed = VALUES(followed),
                            source_event_id = VALUES(source_event_id),
                            updated_at = CURRENT_TIMESTAMP
                        """)
                .param("viewerId", viewerId)
                .param("authorId", authorId)
                .param("followed", "FOLLOWED".equals(status))
                .param("eventId", eventId)
                .update();
    }

    @Override
    public Set<Long> followedAuthorIds(long viewerId) {
        if (viewerId <= 0) return Set.of();
        return jdbcClient.sql("""
                        SELECT author_id
                        FROM recommend_follow_projection
                        WHERE viewer_id = :viewerId AND followed = true
                        """)
                .param("viewerId", viewerId)
                .query(Long.class)
                .list()
                .stream()
                .collect(Collectors.toUnmodifiableSet());
    }
}
