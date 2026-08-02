package io.github.devcircle.recommend.application;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.common.model.ExposureRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FeedServiceWriteValidationTest {
    private FeedService service;

    @BeforeEach
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        service = new FeedService(new RecommendationScorer(), redis,
                mock(RecommendationImpressionRepository.class),
                "http://localhost:8103");
    }

    @Test
    void emptyExposureStillRequiresAnAuthenticatedIdempotentWrite() {
        assertCode(() -> service.recordExposure(new ExposureRequest(0L, List.of(), "home"), "key"),
                ErrorCode.UNAUTHORIZED);
        assertCode(() -> service.recordExposure(new ExposureRequest(7L, List.of(), "home"), ""),
                ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void recommendationWritesRejectInvalidPostIdentifiers() {
        assertCode(() -> service.recordExposure(new ExposureRequest(7L, List.of(0L), "home"), "exposure-key"),
                ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.recordRecommendEvent(7L, null, "click", "home", null, null, "event-key"),
                ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void recommendationEventResponseIsStableAcrossAnIdempotentReplay() {
        var first = service.recordRecommendEvent(7L, 11L, "click", "home", null, null, "event-key");
        var replay = service.recordRecommendEvent(7L, 11L, "click", "home", null, null, "event-key");

        assertThat(replay.get("eventId")).isEqualTo(first.get("eventId"));
    }

    private void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(expected));
    }
}
