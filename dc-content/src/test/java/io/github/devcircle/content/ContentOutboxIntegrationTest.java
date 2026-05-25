package io.github.devcircle.content;

import io.github.devcircle.content.api.CreatePostRequest;
import io.github.devcircle.content.infrastructure.PostRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=localhost:65535"
})
class ContentOutboxIntegrationTest {
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
    PostRepository postRepository;

    @Autowired
    JdbcClient jdbcClient;

    @Test
    void creatingPostWritesContentTagsAndOutboxInOnePath() {
        var created = postRepository.create(42L, new CreatePostRequest(
                "Testcontainers 验证 Outbox",
                "发帖时必须同时写入帖子正文、标签关系和 outbox_event。",
                List.of("Kafka", "MySQL")
        ));

        assertThat(created.id()).isPositive();
        assertThat(postRepository.detail(created.id()).tags()).containsExactlyInAnyOrder("Kafka", "MySQL");

        Long postContentCount = jdbcClient.sql("SELECT COUNT(*) FROM post_content WHERE post_id = :postId")
                .param("postId", created.id())
                .query(Long.class)
                .single();
        Long outboxCount = jdbcClient.sql("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = :postId AND topic = 'post.created' AND status = 'NEW'")
                .param("postId", created.id().toString())
                .query(Long.class)
                .single();

        assertThat(postContentCount).isEqualTo(1);
        assertThat(outboxCount).isEqualTo(1);
    }
}
