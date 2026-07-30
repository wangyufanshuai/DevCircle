package io.github.devcircle.recommend.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcFollowSignalStoreTest {
    private JdbcFollowSignalStore store;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:follow-projection;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        JdbcClient jdbcClient = JdbcClient.create(dataSource);
        jdbcClient.sql("DROP TABLE IF EXISTS recommend_follow_projection").update();
        jdbcClient.sql("""
                        CREATE TABLE recommend_follow_projection (
                            viewer_id BIGINT NOT NULL,
                            author_id BIGINT NOT NULL,
                            followed BOOLEAN NOT NULL,
                            source_event_id VARCHAR(36) NOT NULL UNIQUE,
                            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                            PRIMARY KEY (viewer_id, author_id)
                        )
                        """).update();
        store = new JdbcFollowSignalStore(jdbcClient);
    }

    @Test
    void persistsStableAuthorIdsAndCanReverseTheProjection() {
        store.apply("evt-follow-1", 2L, 9_000_000_001L, "FOLLOWED");
        assertThat(store.followedAuthorIds(2L)).containsExactly(9_000_000_001L);

        store.apply("evt-follow-2", 2L, 9_000_000_001L, "UNFOLLOWED");
        assertThat(store.followedAuthorIds(2L)).isEmpty();
    }

    @Test
    void replayingTheSameEventDoesNotCreateAnotherRelationship() {
        store.apply("evt-follow-retry", 2L, 7L, "FOLLOWED");
        store.apply("evt-follow-retry", 2L, 7L, "FOLLOWED");

        assertThat(store.followedAuthorIds(2L)).containsExactly(7L);
    }
}
