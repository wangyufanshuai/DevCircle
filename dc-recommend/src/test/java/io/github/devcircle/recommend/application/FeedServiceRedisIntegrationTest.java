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
        redisTemplate.opsForSet().add("user:" + userId + ":followees", "lin");
        redisTemplate.opsForHash().put("user:" + userId + ":graph-features", "mutual:lin", "3");
        redisTemplate.opsForZSet().incrementScore("feed:hot-posts", "2", 80.0);

        var kafkaPost = new PostSummary(1L, "Kafka 可靠事件", "Outbox 和消费端幂等。", "lin", List.of("Kafka"), 1, 1, 100, "PUBLISHED", Instant.now());
        var redisPost = new PostSummary(2L, "Redis 热点缓存", "缓存、限流和热度。", "zhou", List.of("Redis"), 1, 1, 100, "PUBLISHED", Instant.now());

        var firstPage = feedService.feedFromPosts(userId, List.of(redisPost, kafkaPost), null, 2);
        assertThat(firstPage.records().getFirst().post().id()).isEqualTo(1L);
        assertThat(firstPage.records().getFirst().algorithm()).isEqualTo("LOCAL_LINEAR_MODEL");
        assertThat(firstPage.records().getFirst().features()).containsKeys("followedAuthor", "mutualFollowees");

        feedService.recordExposure(new ExposureRequest(userId, List.of(1L), "home_feed"));
        var afterExposure = feedService.feedFromPosts(userId, List.of(redisPost, kafkaPost), null, 2);

        assertThat(afterExposure.records()).extracting(item -> item.post().id()).doesNotContain(1L);
        assertThat(afterExposure.records()).extracting(item -> item.post().id()).contains(2L);
    }

    @Test
    void sceneFiltersImageAndVideoFeeds() {
        var imagePost = new PostSummary(10L, "IMAGE", "索引图解", "多图说明搜索链路。", "chen", List.of("搜索"), List.of(), 1, 1, 100, "PUBLISHED", Instant.now());
        var videoPost = new PostSummary(11L, "VIDEO", "事件视频", "视频讲解 Kafka。", "wang", List.of("Kafka"), List.of(), 1, 1, 100, "PUBLISHED", Instant.now());

        var imageFeed = feedService.feedFromPosts(88L, List.of(imagePost, videoPost), null, 10, "image");
        var videoFeed = feedService.feedFromPosts(88L, List.of(imagePost, videoPost), null, 10, "video");

        assertThat(imageFeed.records()).extracting(item -> item.post().type()).containsExactly("IMAGE");
        assertThat(videoFeed.records()).extracting(item -> item.post().type()).containsExactly("VIDEO");
    }
}
