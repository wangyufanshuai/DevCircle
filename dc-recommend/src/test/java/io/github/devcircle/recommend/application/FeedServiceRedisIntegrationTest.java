package io.github.devcircle.recommend.application;

import io.github.devcircle.common.model.ExposureRequest;
import io.github.devcircle.common.model.PostSummary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class FeedServiceRedisIntegrationTest {
    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:8.2")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    FeedService feedService;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Test
    void redisSignalsAffectRankingAndExposureRemovesSeenPosts() {
        Long userId = 77L;
        redisTemplate.opsForSet().add("user:" + userId + ":interest-tags", "Kafka");
        redisTemplate.opsForZSet().incrementScore("feed:hot-posts", "2", 80.0);

        var kafkaPost = new PostSummary(1L, "Kafka 可靠事件", "Outbox 和消费端幂等。", "lin", List.of("Kafka"), 1, 1, 100, "PUBLISHED", Instant.now());
        var redisPost = new PostSummary(2L, "Redis 热点缓存", "缓存、限流和热度。", "zhou", List.of("Redis"), 1, 1, 100, "PUBLISHED", Instant.now());

        var firstPage = feedService.feedFromPosts(userId, List.of(redisPost, kafkaPost), null, 2);
        assertThat(firstPage.records().getFirst().post().id()).isEqualTo(2L);

        feedService.recordExposure(new ExposureRequest(userId, List.of(2L), "home_feed"));
        var afterExposure = feedService.feedFromPosts(userId, List.of(redisPost, kafkaPost), null, 2);

        assertThat(afterExposure.records()).extracting(item -> item.post().id()).doesNotContain(2L);
        assertThat(afterExposure.records()).extracting(item -> item.post().id()).contains(1L);
    }
}
