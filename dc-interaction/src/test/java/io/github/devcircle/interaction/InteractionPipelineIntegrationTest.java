package io.github.devcircle.interaction;

import io.github.devcircle.interaction.application.InteractionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=localhost:65535"
})
@Sql(scripts = {
        "classpath:db/migration/V1__interaction_schema.sql",
        "classpath:db/migration/V2__interaction_outbox.sql"
})
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

    @Test
    void duplicateLikeIsIdempotentAndRedisHeatChangesOnce() {
        var first = interactionService.like(7L, 9001L, "like-9001");
        var duplicate = interactionService.like(7L, 9001L, "like-9001");

        Long likeRows = jdbcClient.sql("SELECT COUNT(*) FROM like_record WHERE user_id = 7 AND target_id = 9001")
                .query(Long.class)
                .single();
        Long likeCount = jdbcClient.sql("SELECT like_count FROM post_counter WHERE post_id = 9001")
                .query(Long.class)
                .single();
        Double hotScore = redisTemplate.opsForZSet().score("feed:hot-posts", "9001");

        assertThat(first.duplicated()).isFalse();
        assertThat(duplicate.duplicated()).isTrue();
        assertThat(likeRows).isEqualTo(1);
        assertThat(likeCount).isEqualTo(1);
        assertThat(hotScore).isEqualTo(3.0);
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
}
