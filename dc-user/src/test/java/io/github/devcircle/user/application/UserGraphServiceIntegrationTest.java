package io.github.devcircle.user.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class UserGraphServiceIntegrationTest {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("dc_user")
            .withUsername("devcircle")
            .withPassword("devcircle");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:8.2")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    UserGraphService service;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    JdbcClient jdbcClient;

    @BeforeEach
    void cleanState() {
        jdbcClient.sql("DELETE FROM user_idempotency").update();
        jdbcClient.sql("DELETE FROM user_follow").update();
        jdbcClient.sql("DELETE FROM user_profile").update();
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    @Test
    void followIsIdempotentAndCachedInRedis() {
        var first = service.follow(1L, 2L, "follow-1-2");
        var duplicate = service.follow(1L, 2L, "follow-1-2");

        assertThat(first.duplicated()).isFalse();
        assertThat(duplicate.duplicated()).isTrue();
        assertThat(service.followees(1L)).containsExactly(2L);
        assertThat(service.followers(2L)).containsExactly(1L);
        assertThat(redisTemplate.opsForSet().isMember("user:1:followees", "2")).isTrue();

        service.unfollow(1L, 2L, "unfollow-1-2");
        assertThat(service.followees(1L)).isEmpty();
    }

    @Test
    void twoHopRecommendationsUseMutualFollowees() {
        service.follow(1L, 10L, "f-1-10");
        service.follow(2L, 10L, "f-2-10");
        service.follow(3L, 10L, "f-3-10");
        service.follow(3L, 11L, "f-3-11");
        service.follow(1L, 11L, "f-1-11");

        var recommendations = service.recommendations(1L);

        assertThat(recommendations).extracting(item -> item.userId()).contains(3L, 2L);
        assertThat(recommendations.getFirst().mutualFollowees()).isGreaterThanOrEqualTo(1L);
        assertThat(recommendations.getFirst().reason()).contains("共同关注");
    }
}
