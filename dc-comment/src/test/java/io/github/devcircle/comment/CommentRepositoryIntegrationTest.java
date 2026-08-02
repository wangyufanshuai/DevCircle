package io.github.devcircle.comment;

import io.github.devcircle.comment.application.PostPublicationGateway;
import io.github.devcircle.comment.infrastructure.CommentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Autowired;
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
        "devcircle.outbox.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(CommentRepositoryIntegrationTest.TestBeans.class)
class CommentRepositoryIntegrationTest {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("dc_comment")
            .withUsername("devcircle")
            .withPassword("devcircle");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    CommentRepository repository;

    @Autowired
    JdbcClient jdbcClient;

    @BeforeEach
    void clean() {
        jdbcClient.sql("DELETE FROM comment").update();
        jdbcClient.sql("DELETE FROM comment_audit_record").update();
        jdbcClient.sql("DELETE FROM outbox_event").update();
        jdbcClient.sql("DELETE FROM idempotency_record").update();
    }

    @Test
    void replayReturnsOriginalCommentEvenAfterParentStateChanges() {
        var root = repository.create(7L, 1001L, null, "root clue", "root-key");
        var reply = repository.create(8L, 1001L, root.id(), "reply clue", "reply-key");
        repository.hide(root.id(), 9L, "hide-key", "moderation reason");

        var replay = repository.create(8L, 1001L, root.id(), "reply clue", "reply-key");

        assertThat(replay.id()).isEqualTo(reply.id());
        assertThat(replay.content()).isEqualTo("reply clue");
        assertThat(outboxCount("comment.created")).isZero();
        assertThat(outboxCount("comment.changed")).isEqualTo(3);
    }

    @Test
    void failedParentValidationRollsBackClaimAndAllowsRetry() {
        var root = repository.create(7L, 1001L, null, "root clue", "root-key");

        assertThatThrownBy(() -> repository.create(8L, 1002L, root.id(), "reply", "retry-key"))
                .isInstanceOf(RuntimeException.class);

        var retry = repository.create(8L, 1001L, root.id(), "reply", "retry-key");

        assertThat(retry.id()).isPositive();
    }

    @Test
    void deletingCommentEmitsOneChangeEventForRepeatedKey() {
        var root = repository.create(7L, 1001L, null, "root clue", "root-key");

        repository.deleteOwn(root.id(), 7L, "delete-key");
        repository.deleteOwn(root.id(), 7L, "delete-key");

        assertThat(jdbcClient.sql("SELECT status FROM comment WHERE id = :id")
                .param("id", root.id()).query(String.class).single()).isEqualTo("DELETED");
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM outbox_event
                        WHERE topic = 'comment.changed'
                          AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.payload.changeType')) = 'DELETED'
                        """).query(Long.class).single()).isEqualTo(1);
    }

    private long outboxCount(String topic) {
        return jdbcClient.sql("SELECT COUNT(*) FROM outbox_event WHERE topic = :topic")
                .param("topic", topic).query(Long.class).single();
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        PostPublicationGateway postPublicationGateway() {
            return postId -> { };
        }
    }
}
