package io.github.devcircle.recommend.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcInteractionConsumerDedupTest {
    private JdbcClient jdbcClient;
    private JdbcInteractionConsumerDedup dedup;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:recommend-dedup;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        jdbcClient = JdbcClient.create(dataSource);
        jdbcClient.sql("DROP TABLE IF EXISTS recommend_consumer_dedup").update();
        jdbcClient.sql("""
                        CREATE TABLE recommend_consumer_dedup (
                            event_id VARCHAR(36) PRIMARY KEY,
                            topic VARCHAR(128) NOT NULL,
                            status VARCHAR(16) NOT NULL,
                            attempts INT NOT NULL DEFAULT 1,
                            last_error VARCHAR(1000),
                            processed_at TIMESTAMP,
                            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                        )
                        """).update();
        dedup = new JdbcInteractionConsumerDedup(jdbcClient);
    }

    @Test
    void failedEventCanBeReclaimedAndCompletedEventCannot() {
        assertThat(dedup.claim("event-1", "interaction.changed")).isTrue();
        dedup.markFailed("event-1", "redis unavailable");
        assertThat(dedup.claim("event-1", "interaction.changed")).isTrue();
        dedup.markCompleted("event-1");

        assertThat(dedup.claim("event-1", "interaction.changed")).isFalse();
        assertThat(attempts("event-1")).isEqualTo(2);
    }

    @Test
    void eventIdCannotBeReusedForAnotherTopic() {
        assertThat(dedup.claim("event-2", "interaction.changed")).isTrue();

        assertThatThrownBy(() -> dedup.claim("event-2", "follow.changed"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("interaction.changed");
        assertThat(attempts("event-2")).isEqualTo(1);
    }

    private int attempts(String eventId) {
        return jdbcClient.sql("SELECT attempts FROM recommend_consumer_dedup WHERE event_id = :eventId")
                .param("eventId", eventId).query(Integer.class).single();
    }
}
