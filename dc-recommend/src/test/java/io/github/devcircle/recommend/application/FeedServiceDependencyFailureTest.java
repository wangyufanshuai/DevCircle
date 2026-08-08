package io.github.devcircle.recommend.application;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.content.contract.PostSummary;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FeedServiceDependencyFailureTest {
    @Test
    void strictProductionModeDoesNotTurnRedisFailureIntoSuccessfulFeed() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForSet()).thenThrow(new IllegalStateException("redis down"));
        RecommendationImpressionRepository impressions = mock(RecommendationImpressionRepository.class);
        when(impressions.activeExperiment(anyString(), anyLong()))
                .thenReturn(Map.of("experimentId", "exp-theme-feed-v1", "bucket", "control"));
        FeedService service = new FeedService(new RecommendationScorer(), redis,
                impressions, "http://localhost:8103");

        PostSummary post = new PostSummary(1L, "TEXT", "DEDUCTION", "CASE_FILE", "MEDIUM", false, 0,
                "测试内容", "摘要", "作者", List.of("推理"), List.of(), 0, 0, 0,
                "PUBLISHED", Instant.now());

        assertThatThrownBy(() -> service.feedFromPosts(2L, List.of(post), null, 10, "home"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.code())
                                .isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE));
    }
}
