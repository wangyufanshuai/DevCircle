package io.github.devcircle.recommend.api;

import io.github.devcircle.recommend.application.FeedService;
import io.github.devcircle.recommend.application.LightweightMlRanker;
import io.github.devcircle.recommend.application.RecommendationScorer;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RecommendControllerTest {
    @Test
    void feedReturnsCursorPageWithReasons() {
        var service = new FeedService(new RecommendationScorer(), new LightweightMlRanker(), mock(StringRedisTemplate.class));
        var response = new RecommendController(service).feed(2L, null, 2, "home");

        assertThat(response.data().records()).hasSize(2);
        assertThat(response.data().records().getFirst().reason()).isNotBlank();
        assertThat(response.data().records().getFirst().algorithm()).isEqualTo("THEME_LINEAR_MODEL");
        assertThat(response.data().nextCursor()).isEqualTo("2");
    }
}
