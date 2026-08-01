package io.github.devcircle.content.infrastructure;

import io.github.devcircle.common.event.EventTopics;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContentProjectionDedupTest {
    private JdbcClient jdbcClient;
    private ContentCommentProjectionRepository comments;
    private ContentInteractionProjectionRepository interactions;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:content-projection-dedup;MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbcClient = JdbcClient.create(dataSource);
        jdbcClient.sql("DROP ALL OBJECTS").update();
        jdbcClient.sql("CREATE TABLE post(id BIGINT PRIMARY KEY, like_count BIGINT DEFAULT 0, comment_count BIGINT DEFAULT 0)")
                .update();
        jdbcClient.sql("INSERT INTO post(id) VALUES (1001)").update();
        jdbcClient.sql("CREATE TABLE comment_projection_dedup(event_id CHAR(36) PRIMARY KEY, topic VARCHAR(128) NOT NULL)")
                .update();
        jdbcClient.sql("CREATE TABLE interaction_projection_dedup(event_id CHAR(36) PRIMARY KEY, topic VARCHAR(128) NOT NULL)")
                .update();
        comments = new ContentCommentProjectionRepository(jdbcClient);
        interactions = new ContentInteractionProjectionRepository(jdbcClient);
    }

    @Test
    void commentEventIdCannotMoveBetweenLegacyAndOrderedTopics() {
        String eventId = "2fc9c5a7-f4c5-41d6-adf7-6f9ef5139e2a";
        assertThat(comments.apply(eventId, EventTopics.COMMENT_CREATED, 1001L, "CREATED")).isTrue();
        assertThat(comments.apply(eventId, EventTopics.COMMENT_CREATED, 1001L, "CREATED")).isFalse();

        assertThatThrownBy(() -> comments.apply(
                eventId, EventTopics.COMMENT_CHANGED, 1001L, "CREATED"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Event ID is already bound to topic comment.created");
        assertThat(commentCount()).isEqualTo(1L);
    }

    @Test
    void interactionEventIdCannotBeSilentlyReusedByAnotherTopic() {
        String eventId = "ef66d1af-3558-4472-b67f-7ee3c90bec16";
        assertThat(interactions.apply(eventId, EventTopics.INTERACTION_CHANGED, 1001L, "LIKE")).isTrue();

        assertThatThrownBy(() -> interactions.apply(
                eventId, EventTopics.FOLLOW_CHANGED, 1001L, "LIKE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Event ID is already bound to topic interaction.changed");
        assertThat(likeCount()).isEqualTo(1L);
    }

    private long commentCount() {
        return jdbcClient.sql("SELECT comment_count FROM post WHERE id = 1001").query(Long.class).single();
    }

    private long likeCount() {
        return jdbcClient.sql("SELECT like_count FROM post WHERE id = 1001").query(Long.class).single();
    }
}
