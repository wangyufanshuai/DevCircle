package io.github.devcircle.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.common.event.ValidatedEvent;
import io.github.devcircle.content.infrastructure.ContentInteractionProjectionRepository;
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
class ContentInteractionProjectionIntegrationTest {
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
    ContentInteractionProjectionRepository repository;

    @Autowired
    JdbcClient jdbcClient;

    @BeforeEach
    void preparePost() {
        jdbcClient.sql("DELETE FROM interaction_projection_dedup").update();
        jdbcClient.sql("DELETE FROM post WHERE id IN (1001, 1002)").update();
        jdbcClient.sql("""
                        INSERT INTO post(id, author_id, title, status, like_count)
                        VALUES (1001, 7, 'Published case', 'PUBLISHED', 0)
                        """).update();
    }

    @Test
    void likeAndUnlikeProjectExactlyOnce() {
        ValidatedEvent like = interactionEvent("11111111-1111-1111-1111-111111111111", 1001L, "LIKE");
        assertThat(repository.apply(like, 1001L, "LIKE")).isTrue();
        assertThat(repository.apply(like, 1001L, "LIKE")).isFalse();
        assertThat(repository.apply(
                interactionEvent("22222222-2222-2222-2222-222222222222", 1001L, "UNLIKE"),
                1001L, "UNLIKE")).isTrue();

        assertThat(likeCount(1001L)).isZero();
        assertThat(dedupCount()).isEqualTo(2);
    }

    @Test
    void failedProjectionRollsBackDedupClaimSoKafkaCanRetry() {
        String eventId = "33333333-3333-3333-3333-333333333333";
        ValidatedEvent event = interactionEvent(eventId, 1002L, "LIKE");
        assertThatThrownBy(() -> repository.apply(event, 1002L, "LIKE"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(dedupCount()).isZero();

        jdbcClient.sql("""
                        INSERT INTO post(id, author_id, title, status, like_count)
                        VALUES (1002, 7, 'Recovered case', 'PUBLISHED', 0)
                        """).update();

        assertThat(repository.apply(event, 1002L, "LIKE")).isTrue();
        assertThat(likeCount(1002L)).isEqualTo(1);
    }

    private ValidatedEvent interactionEvent(String eventId, long postId, String interactionType) {
        return new ValidatedEvent(UUID.fromString(eventId), EventTopics.INTERACTION_CHANGED, 1,
                Instant.parse("2026-08-01T10:15:30Z"), "dc-interaction", Long.toString(postId),
                OBJECT_MAPPER.createObjectNode()
                        .put("actorId", 7L)
                        .put("postId", postId)
                        .put("interactionType", interactionType));
    }

    private long likeCount(Long postId) {
        Long value = jdbcClient.sql("SELECT like_count FROM post WHERE id = :postId")
                .param("postId", postId).query(Long.class).single();
        return value == null ? 0 : value;
    }

    private long dedupCount() {
        Long value = jdbcClient.sql("SELECT COUNT(*) FROM interaction_projection_dedup")
                .query(Long.class).single();
        return value == null ? 0 : value;
    }
}
