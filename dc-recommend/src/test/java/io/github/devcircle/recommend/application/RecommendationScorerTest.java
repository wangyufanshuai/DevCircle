package io.github.devcircle.recommend.application;

import io.github.devcircle.content.contract.PostSummary;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RecommendationScorerTest {
    private final RecommendationScorer scorer = new RecommendationScorer();

    @Test
    void interestAndHeatIncreaseScoreWhileExposurePenalizes() {
        var post = new PostSummary(1L, "Kafka Feed", "事件驱动推荐", "lin", List.of("Kafka"), 100, 20, 3000, "PUBLISHED", Instant.now());

        double fresh = scorer.score(post, Set.of("Kafka"), 30, false, false);
        double exposed = scorer.score(post, Set.of("Kafka"), 30, false, true);
        double unrelated = scorer.score(post, Set.of("Redis"), 0, false, false);

        assertThat(fresh).isGreaterThan(unrelated);
        assertThat(exposed).isLessThan(0);
    }
}
