package io.github.devcircle.recommend.application;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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
        int inserted = jdbcClient.sql("""
                        INSERT IGNORE INTO recommend_follow_projection(viewer_id, author_id, followed, source_event_id)
                        VALUES (:viewerId, :authorId, :followed, :eventId)
                        """)
                .param("viewerId", viewerId)
                .param("authorId", authorId)
                .param("followed", "FOLLOWED".equals(status))
                .param("eventId", eventId)
                .update();
        if (inserted > 0) return;

        List<ProjectionIdentity> sameEvent = jdbcClient.sql("""
                        SELECT viewer_id, author_id
                        FROM recommend_follow_projection
                        WHERE source_event_id = :eventId
                        """)
                .param("eventId", eventId)
                .query((rs, rowNum) -> new ProjectionIdentity(rs.getLong("viewer_id"), rs.getLong("author_id")))
                .list();
        if (!sameEvent.isEmpty()) {
            ProjectionIdentity existing = sameEvent.getFirst();
            if (existing.viewerId() != viewerId || existing.authorId() != authorId) {
                throw new IllegalArgumentException("Follow event identity does not match its projection");
            }
            return;
        }

        int updated = jdbcClient.sql("""
                        UPDATE recommend_follow_projection
                        SET followed = :followed, source_event_id = :eventId, updated_at = CURRENT_TIMESTAMP
                        WHERE viewer_id = :viewerId AND author_id = :authorId
                        """)
                .param("viewerId", viewerId)
                .param("authorId", authorId)
                .param("followed", "FOLLOWED".equals(status))
                .param("eventId", eventId)
                .update();
        if (updated == 0) {
            throw new IllegalStateException("Follow projection disappeared while applying event " + eventId);
        }
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

    private record ProjectionIdentity(long viewerId, long authorId) {
    }
}
