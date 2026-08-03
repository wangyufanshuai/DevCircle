package io.github.devcircle.interaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.interaction.application.InteractionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import io.github.devcircle.interaction.application.PostPublicationGateway;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=localhost:65535",
        "devcircle.outbox.poll-interval-ms=60000",
        "devcircle.outbox.initial-delay-ms=3600000"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(InteractionPipelineIntegrationTest.PublicationTestConfig.class)
class InteractionPipelineIntegrationTest {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("dc_interaction")
            .withUsername("devcircle")
            .withPassword("devcircle");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:8.2")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    InteractionService interactionService;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @TestConfiguration
    static class PublicationTestConfig {
        @Bean
        @Primary
        PostPublicationGateway postPublicationGateway() {
            return postId -> new PostPublicationGateway.PublishedPost(postId, postId == 9004L ? 17 : 0);
        }
    }

    @Test
    void duplicateLikeIsIdempotentAndRedisHeatChangesOnce() throws Exception {
        var first = interactionService.like(7L, 9001L, "like-9001");
        var duplicate = interactionService.like(7L, 9001L, "like-9001");

        Long likeRows = jdbcClient.sql("SELECT COUNT(*) FROM like_record WHERE user_id = 7 AND target_id = 9001")
                .query(Long.class)
                .single();
        Long likeCount = jdbcClient.sql("SELECT like_count FROM post_counter WHERE post_id = 9001")
                .query(Long.class)
                .single();
        Long interactionEvents = jdbcClient.sql("SELECT COUNT(*) FROM outbox_event WHERE topic = 'interaction.changed'")
                .query(Long.class).single();

        assertThat(first.duplicated()).isFalse();
        assertThat(duplicate.duplicated()).isTrue();
        assertThat(likeRows).isEqualTo(1);
        assertThat(likeCount).isEqualTo(1);
        assertThat(interactionEvents).isEqualTo(1L);
        String payload = jdbcClient.sql("""
                        SELECT payload FROM outbox_event WHERE topic = 'interaction.changed'
                        """).query(String.class).single();
        assertThat(objectMapper.readTree(payload).path("payload").path("likeCount").asLong()).isEqualTo(1L);
        assertThat(objectMapper.readTree(payload).path("payload").path("counterVersion").asLong()).isEqualTo(1L);
    }

    @Test
    void duplicateFavoriteIsIdempotent() {
        interactionService.favorite(8L, 9002L, "favorite-9002");
        interactionService.favorite(8L, 9002L, "favorite-9002");

        Long favoriteRows = jdbcClient.sql("SELECT COUNT(*) FROM favorite_record WHERE user_id = 8 AND post_id = 9002")
                .query(Long.class)
                .single();
        Long favoriteCount = jdbcClient.sql("SELECT favorite_count FROM post_counter WHERE post_id = 9002")
                .query(Long.class)
                .single();

        assertThat(favoriteRows).isEqualTo(1);
        assertThat(favoriteCount).isEqualTo(1);
    }

    @Test
    void stateReturnsDurableSelectionAndCountersAfterToggle() {
        interactionService.like(9L, 9003L, "like-9003");
        interactionService.favorite(9L, 9003L, "favorite-9003");

        var state = jdbcClient.sql("SELECT like_count, favorite_count FROM post_counter WHERE post_id = 9003")
                .query((rs, rowNum) -> java.util.List.of(rs.getLong("like_count"), rs.getLong("favorite_count")))
                .single();

        assertThat(state).containsExactly(1L, 1L);
    }

    @Test
    void firstRealLikePreservesThePublishedContentBaseline() {
        interactionService.like(10L, 9004L, "like-9004");

        Long likeCount = jdbcClient.sql("SELECT like_count FROM post_counter WHERE post_id = 9004")
                .query(Long.class).single();

        assertThat(likeCount).isEqualTo(18);
    }
}
