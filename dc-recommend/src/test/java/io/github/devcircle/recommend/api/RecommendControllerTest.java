package io.github.devcircle.recommend.api;

import io.github.devcircle.recommend.application.FeedService;
import io.github.devcircle.recommend.application.RecommendationScorer;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RecommendControllerTest {
    @Test
    void feedReturnsCursorPageWithReasons() {
        var service = new FeedService(new RecommendationScorer(), mock(StringRedisTemplate.class));
        var response = new RecommendController(service).feed(2L, null, 2, "home");

        assertThat(response.data().records()).hasSize(2);
        assertThat(response.data().records().getFirst().reason()).isNotBlank();
        assertThat(response.data().records().getFirst().algorithm()).isEqualTo("EXPLAINABLE_RULE_V1");
        assertThat(response.data().nextCursor()).isEqualTo("2");
    }

    @Test
    void feedUsesExpandedThemeLibrary() {
        var service = new FeedService(new RecommendationScorer(), mock(StringRedisTemplate.class));

        var firstPage = new RecommendController(service).feed(2L, null, 50, "mixed");
        var secondPage = new RecommendController(service).feed(2L, firstPage.data().nextCursor(), 50, "mixed");
        var thirdPage = new RecommendController(service).feed(2L, secondPage.data().nextCursor(), 50, "mixed");
        var fourthPage = new RecommendController(service).feed(2L, thirdPage.data().nextCursor(), 50, "mixed");

        assertThat(firstPage.data().records()).hasSize(50);
        assertThat(secondPage.data().records()).hasSize(50);
        assertThat(thirdPage.data().records()).hasSize(50);
        assertThat(fourthPage.data().records()).hasSize(30);
    }

    @Test
    void feedClampsInvalidPageSizesInsteadOfReturningServerErrors() {
        var service = new FeedService(new RecommendationScorer(), mock(StringRedisTemplate.class));
        var controller = new RecommendController(service);

        assertThat(controller.feed(2L, null, -10, "home").data().records()).hasSize(1);
        assertThat(controller.feed(2L, null, 500, "home").data().records()).hasSize(50);
    }
}
