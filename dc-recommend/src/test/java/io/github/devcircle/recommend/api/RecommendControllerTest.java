package io.github.devcircle.recommend.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecommendControllerTest {
    @Test
    void feedReturnsExplainableRecommendations() {
        var response = new RecommendController().feed();

        assertThat(response.data()).hasSize(2);
        assertThat(response.data().getFirst().reason()).contains("关注");
        assertThat(response.data().getFirst().score()).isGreaterThan(0.8);
    }
}

