package io.github.devcircle.recommend.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.common.model.FeedItem;
import io.github.devcircle.common.model.PostSummary;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecommendationImpressionRepositoryFailureTest {
    @Test
    void strictModeFailsWhenExperimentStorageIsUnavailable() {
        RecommendationImpressionRepository repository = repository(false);

        assertThatThrownBy(() -> repository.activeExperiment("home", 2L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE));
    }

    @Test
    void strictModeDoesNotReportSuccessfulImpressionWhenStorageIsUnavailable() {
        RecommendationImpressionRepository repository = repository(false);

        assertThatThrownBy(() -> repository.save(2L, "home", feedItem()))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE));
    }

    @Test
    void explicitDevelopmentFallbackKeepsTheKnownExperimentDefault() {
        RecommendationImpressionRepository repository = repository(true);

        assertThat(repository.activeExperiment("home", 2L))
                .containsEntry("experimentId", "exp-theme-feed-v1")
                .containsEntry("bucket", "control");
        assertThatCode(() -> repository.save(2L, "home", feedItem())).doesNotThrowAnyException();
    }

    private RecommendationImpressionRepository repository(boolean fallbackEnabled) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:recommend-failure-" + fallbackEnabled + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        return new RecommendationImpressionRepository(JdbcClient.create(dataSource), new ObjectMapper(), fallbackEnabled);
    }

    private FeedItem feedItem() {
        PostSummary post = new PostSummary(1001L, "TEXT", "DEDUCTION", "CASE_FILE", "MEDIUM", false, 0,
                "雾港钟楼失踪案", "摘要", "林墨", List.of("推理"), List.of(), 0, 0, 0,
                "PUBLISHED", Instant.now());
        return new FeedItem(post, "编辑精选", 80, false, false, "EXPLAINABLE_RULE_V1", "TEXT",
                Map.of("quality", 1.0), "exp-theme-feed-v1", "control", List.of("theme"));
    }
}
