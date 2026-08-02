package io.github.devcircle.content;

import io.github.devcircle.common.event.EventTopics;
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
        jdbcClient.sql("DELETE FROM comment_projection_dedup").update();
        jdbcClient.sql("DELETE FROM post WHERE id IN (92001, 92002)").update();
        jdbcClient.sql("INSERT INTO post(id, author_id, title, status, comment_count) VALUES (92001, 7, 'Comments', 'PUBLISHED', 0)").update();
    }

    @Test
    void createDeleteAndDuplicateEventsKeepCountConsistent() {
        assertThat(repository.apply("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", EventTopics.COMMENT_CHANGED,
                92001L, "CREATED")).isTrue();
        assertThat(repository.apply("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", EventTopics.COMMENT_CHANGED,
                92001L, "CREATED")).isFalse();
        assertThat(repository.apply("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", EventTopics.COMMENT_CHANGED,
                92001L, "DELETED")).isTrue();

        assertThat(commentCount(92001L)).isZero();
        assertThat(dedupCount()).isEqualTo(2);
    }

    @Test
    void missingPostRollsBackDedupClaimForRetry() {
        String eventId = "cccccccc-cccc-cccc-cccc-cccccccccccc";
        assertThatThrownBy(() -> repository.apply(eventId, EventTopics.COMMENT_CREATED, 92002L, "CREATED"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(dedupCount()).isZero();
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
