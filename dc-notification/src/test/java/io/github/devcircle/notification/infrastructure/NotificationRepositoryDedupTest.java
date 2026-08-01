package io.github.devcircle.notification.infrastructure;

import io.github.devcircle.common.event.EventTopics;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationRepositoryDedupTest {
    private JdbcClient jdbcClient;
    private NotificationRepository repository;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:notification-dedup;MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbcClient = JdbcClient.create(dataSource);
        jdbcClient.sql("DROP ALL OBJECTS").update();
        jdbcClient.sql("""
                CREATE TABLE consumer_dedup (
                    event_id CHAR(36) PRIMARY KEY,
                    topic VARCHAR(128) NOT NULL,
                    consumed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL)
                """).update();
        jdbcClient.sql("""
                CREATE TABLE notification (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    receiver_id BIGINT NOT NULL,
                    actor_id BIGINT NULL,
                    type VARCHAR(64) NOT NULL,
                    title VARCHAR(128) NOT NULL,
                    content VARCHAR(512) NOT NULL,
                    target_type VARCHAR(32) NULL,
                    target_id BIGINT NULL,
                    target_route VARCHAR(255) NULL,
                    read_flag BOOLEAN DEFAULT FALSE NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL)
                """).update();
        repository = new NotificationRepository(jdbcClient);
    }

    @Test
    void duplicateTopicIsIdempotentButCrossTopicReuseIsRejected() {
        String eventId = "f3887f15-5f26-45e5-86ee-8657eea6ac6f";

        assertThat(repository.createOnce(eventId, EventTopics.INTERACTION_CHANGED, 7L, 2L,
                "INTERACTION", "title", "content", "POST", 9001L, "/posts/9001")).isTrue();
        assertThat(repository.createOnce(eventId, EventTopics.INTERACTION_CHANGED, 7L, 2L,
                "INTERACTION", "ignored", "ignored", "POST", 9001L, "/posts/9001")).isFalse();

        assertThatThrownBy(() -> repository.acknowledgeOnce(eventId, EventTopics.FOLLOW_CHANGED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Event ID is already bound to topic interaction.changed");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM notification").query(Long.class).single()).isEqualTo(1L);
    }
}
