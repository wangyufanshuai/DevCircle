package io.github.devcircle.recommend.application;

import io.github.devcircle.common.model.ExposureRequest;
import io.github.devcircle.common.model.PostSummary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "devcircle.recommend.read-fallback-enabled=true",
        "spring.kafka.listener.auto-startup=false",
        "devcircle.kafka.topics.enabled=false"
})
@Sql(statements = {
        "CREATE TABLE IF NOT EXISTS recommend_write_idempotency (idempotency_key VARCHAR(128) PRIMARY KEY, user_id BIGINT NOT NULL, operation VARCHAR(48) NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
        "CREATE TABLE IF NOT EXISTS recommendation_exposure (user_id BIGINT NOT NULL, post_id BIGINT NOT NULL, scene VARCHAR(64) NOT NULL, exposed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (user_id, post_id, scene))"
})
class FeedServiceRedisIntegrationTest {
    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:8.2")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:recommend;MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
    }

    @Autowired
    FeedService feedService;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Test
    void redisSignalsAffectRankingAndExposureDownranksSeenPosts() {
        Long userId = 77L;
        redisTemplate.opsForSet().add("user:" + userId + ":interest-tags", "Kafka");
        redisTemplate.opsForSet().add("user:" + userId + ":followees", "lin");
        redisTemplate.opsForHash().put("user:" + userId + ":graph-features", "mutual:lin", "3");
        redisTemplate.opsForZSet().incrementScore("feed:hot-posts", "2", 80.0);

        var kafkaPost = new PostSummary(1L, "Kafka 可靠事件", "Outbox 和消费端幂等。", "lin", List.of("Kafka"), 1, 1, 100, "PUBLISHED", Instant.now());
        var redisPost = new PostSummary(2L, "Redis 热点缓存", "缓存、限流和热度。", "zhou", List.of("Redis"), 1, 1, 100, "PUBLISHED", Instant.now());

        var firstPage = feedService.feedFromPosts(userId, List.of(redisPost, kafkaPost), null, 2);
        assertThat(firstPage.records().getFirst().post().id()).isEqualTo(1L);
        assertThat(firstPage.records().getFirst().algorithm()).isEqualTo("EXPLAINABLE_RULE_V1");
        assertThat(firstPage.records().getFirst().features()).containsKeys(
                "themeInterest35", "followedAuthor20", "qualityHotness20", "freshness15", "exploration10");

        feedService.recordExposure(new ExposureRequest(userId, List.of(1L), "home_feed"));
        var afterExposure = feedService.feedFromPosts(userId, List.of(redisPost, kafkaPost), null, 2);

        assertThat(afterExposure.records()).extracting(item -> item.post().id()).containsExactly(2L, 1L);
        assertThat(afterExposure.records().getLast().features().get("exposurePenalty")).isEqualTo(100.0);
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

    @Test
    void sceneFiltersThemeFeeds() {
        var deductionPost = new PostSummary(20L, "TEXT", "DEDUCTION", "CASE_FILE", "HARD", true, 4,
                "雾港钟楼失踪案", "密室案件", "lin", List.of("密室"), List.of(), 1, 1, 100, "PUBLISHED", Instant.now());
        var sciFiPost = new PostSummary(21L, "TEXT", "SCI_FI", "FICTION", "MEDIUM", false, 0,
                "火星雨季", "科幻短篇", "chen", List.of("火星"), List.of(), 1, 1, 100, "PUBLISHED", Instant.now());
        var cosmosPost = new PostSummary(22L, "TEXT", "COSMOS", "COSMIC_ARTICLE", "MEDIUM", false, 0,
                "银河系旋臂档案", "宇宙档案", "wang", List.of("银河系"), List.of(), 1, 1, 100, "PUBLISHED", Instant.now());

        assertThat(feedService.feedFromPosts(88L, List.of(deductionPost, sciFiPost, cosmosPost), null, 10, "deduction").records())
                .extracting(item -> item.post().theme()).containsExactly("DEDUCTION");
        assertThat(feedService.feedFromPosts(88L, List.of(deductionPost, sciFiPost, cosmosPost), null, 10, "sci_fi").records())
                .extracting(item -> item.post().theme()).containsExactly("SCI_FI");
        assertThat(feedService.feedFromPosts(88L, List.of(deductionPost, sciFiPost, cosmosPost), null, 10, "cosmos").records())
                .extracting(item -> item.post().theme()).containsExactly("COSMOS");
    }
}
