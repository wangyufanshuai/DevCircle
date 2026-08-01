package io.github.devcircle.recommend.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecommendationImpressionRepositoryStateTest {
    private JdbcClient jdbc;
    private RecommendationImpressionRepository repository;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:recommend-state;MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("DROP ALL OBJECTS").update();
        jdbc.sql("""
                CREATE TABLE recommend_write_idempotency (
                    idempotency_key VARCHAR(128) PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    operation VARCHAR(48) NOT NULL,
                    request_fingerprint CHAR(64) NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
                """).update();
        jdbc.sql("""
                CREATE TABLE recommendation_exposure (
                    user_id BIGINT NOT NULL,
                    post_id BIGINT NOT NULL,
                    scene VARCHAR(64) NOT NULL,
                    exposed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (user_id, post_id, scene))
                """).update();
        jdbc.sql("""
                CREATE TABLE recommend_event_log (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    post_id BIGINT NOT NULL,
                    event_type VARCHAR(64) NOT NULL,
                    scene VARCHAR(64) NOT NULL,
                    experiment_id VARCHAR(64),
                    bucket VARCHAR(64),
                    request_id VARCHAR(128),
                    UNIQUE (user_id, request_id))
                """).update();
        jdbc.sql("""
                CREATE TABLE outbox_event (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    event_id VARCHAR(36) NOT NULL UNIQUE,
                    topic VARCHAR(128) NOT NULL,
                    aggregate_type VARCHAR(64) NOT NULL,
                    aggregate_id VARCHAR(64) NOT NULL,
                    payload JSON NOT NULL)
                """).update();
        repository = new RecommendationImpressionRepository(jdbc, new ObjectMapper().findAndRegisterModules(), false);
    }

    @Test
    void exposureKeyIsBoundToTheNormalizedPostSetAndScene() {
        assertThat(repository.saveExposure(7L, List.of(12L, 11L, 12L), "home", "exposure-key"))
                .isEqualTo(2);
        assertThat(repository.saveExposure(7L, List.of(11L, 12L), "home", "exposure-key"))
                .isZero();
        assertThat(count("recommendation_exposure")).isEqualTo(2);

        assertDuplicate(() -> repository.saveExposure(7L, List.of(13L), "home", "exposure-key"));
        assertDuplicate(() -> repository.saveExposure(7L, List.of(11L, 12L), "deduction", "exposure-key"));
    }

    @Test
    void eventKeyCannotBeReplayedWithAConflictingPayload() {
        repository.saveRecommendEvent(7L, 11L, "click", "home", "exp-v1", "control", "event-key");
        repository.saveRecommendEvent(7L, 11L, "click", "home", "exp-v1", "control", "event-key");

        assertThat(count("recommend_event_log")).isOne();
        assertThat(count("outbox_event")).isOne();
        assertDuplicate(() -> repository.saveRecommendEvent(
                7L, 12L, "click", "home", "exp-v1", "control", "event-key"));
    }

    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    private void assertDuplicate(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.DUPLICATE_OPERATION));
    }
}
