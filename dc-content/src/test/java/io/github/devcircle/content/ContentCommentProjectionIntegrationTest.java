package io.github.devcircle.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.common.event.ValidatedEvent;
import io.github.devcircle.content.infrastructure.ContentCommentProjectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=127.0.0.1:1",
        "spring.kafka.listener.auto-startup=false",
        "devcircle.kafka.topics.enabled=false",
        "devcircle.outbox.initial-delay-ms=3600000"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ContentCommentProjectionIntegrationTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("dc_content")
            .withUsername("devcircle")
            .withPassword("devcircle");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    ContentCommentProjectionRepository repository;

    @Autowired
    JdbcClient jdbcClient;

    @BeforeEach
    void preparePost() {
        jdbcClient.sql("DELETE FROM content_counter_projection_watermark WHERE counter_type = 'COMMENT'").update();
        jdbcClient.sql("DELETE FROM comment_projection_dedup").update();
        jdbcClient.sql("DELETE FROM post WHERE id IN (92001, 92002)").update();
        jdbcClient.sql("INSERT INTO post(id, author_id, title, status, comment_count) VALUES (92001, 7, 'Comments', 'PUBLISHED', 0)").update();
    }

    @Test
    void createDeleteAndDuplicateEventsKeepCountConsistent() {
        ValidatedEvent created = commentEvent(
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", 9L, 92001L, "CREATED");
        assertThat(repository.apply(created, 92001L, "CREATED")).isTrue();
        assertThat(repository.apply(created, 92001L, "CREATED")).isFalse();
        assertThat(repository.apply(commentEvent(
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", 9L, 92001L, "DELETED"),
                92001L, "DELETED")).isTrue();

        assertThat(commentCount(92001L)).isZero();
        assertThat(dedupCount()).isEqualTo(2);
    }

    @Test
    void missingPostRollsBackDedupClaimForRetry() {
        String eventId = "cccccccc-cccc-cccc-cccc-cccccccccccc";
        ValidatedEvent event = commentEvent(eventId, 10L, 92002L, "CREATED");
        assertThatThrownBy(() -> repository.apply(event, 92002L, "CREATED"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(dedupCount()).isZero();
    }

    private ValidatedEvent commentEvent(String eventId, long commentId, long postId, String changeType) {
        return new ValidatedEvent(UUID.fromString(eventId), EventTopics.COMMENT_CHANGED, 1,
                Instant.parse("2026-08-01T10:15:30Z"), "dc-comment", Long.toString(commentId),
                OBJECT_MAPPER.createObjectNode()
                        .put("commentId", commentId)
                        .put("postId", postId)
                        .put("changeType", changeType));
    }

    private long commentCount(Long postId) {
        return jdbcClient.sql("SELECT comment_count FROM post WHERE id = :postId")
                .param("postId", postId).query(Long.class).single();
    }

    private long dedupCount() {
        return jdbcClient.sql("SELECT COUNT(*) FROM comment_projection_dedup")
                .query(Long.class).single();
    }
}
