package io.github.devcircle.recommend.application;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.common.model.PostSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedServiceContractTest {
    private StringRedisTemplate redis;
    private SetOperations<String, String> sets;
    private ZSetOperations<String, String> sortedSets;
    private ListOperations<String, String> lists;
    private HashOperations<String, Object, Object> hashes;
    private RecommendationImpressionRepository impressions;
    private FollowSignalStore follows;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        sets = mock(SetOperations.class);
        sortedSets = mock(ZSetOperations.class);
        lists = mock(ListOperations.class);
        hashes = mock(HashOperations.class);
        impressions = mock(RecommendationImpressionRepository.class);
        follows = mock(FollowSignalStore.class);

        when(redis.opsForSet()).thenReturn(sets);
        when(redis.opsForZSet()).thenReturn(sortedSets);
        when(redis.opsForList()).thenReturn(lists);
        when(redis.opsForHash()).thenReturn(hashes);
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);
        when(sets.members(anyString())).thenReturn(Set.of());
        when(sortedSets.score(anyString(), anyString())).thenReturn(null);
        when(follows.followedAuthorIds(anyLong())).thenReturn(Set.of());
        when(impressions.activeExperiment(anyString(), anyLong()))
                .thenReturn(Map.of("experimentId", "exp-contract", "bucket", "control"));
    }

    @Test
    void fullyExposedColdCandidatesAreRecoveredAsAnExplicitlyDegradedPage() {
        when(sets.members("user:7:exposed-posts")).thenReturn(Set.of("101"));
        FeedService service = service();
        PostSummary cold = post(101L, "SCI_FI", null, List.of(), 0, List.of(), Instant.EPOCH);

        var page = service.feedFromPosts(7L, List.of(cold), "not-a-cursor", 0, "home");

        assertThat(page.records()).hasSize(1);
        assertThat(page.records().getFirst().post().id()).isEqualTo(101L);
        assertThat(page.degraded()).isTrue();
        assertThat(page.source()).isEqualTo("DB");
        assertThat(page.nextCursor()).isEqualTo("1");
    }

    @Test
    void feedExplainsEveryRecallSignalThatActuallyAffectedTheCandidate() {
        when(sets.members("user:7:interest-tags")).thenReturn(Set.of("Kafka"));
        when(sortedSets.score("feed:hot-posts", "1")).thenReturn(80.0);
        when(follows.followedAuthorIds(7L)).thenReturn(Set.of(701L));
        FeedService service = service();
        PostSummary post = post(1L, "DEDUCTION", 701L, List.of("Kafka"), 101, List.of(2L), Instant.now());

        var item = service.feedFromPosts(7L, List.of(post), null, 10, "deduction").records().getFirst();

        assertThat(item.reason()).isEqualTo("与你近期感兴趣的主题相关");
        assertThat(item.recallSources()).containsExactly(
                "theme", "tag", "hot", "follow_author", "series");
        assertThat(item.features()).containsKeys(
                "themeInterest35", "followedAuthor20", "qualityHotness20", "freshness15", "exploration10");
    }

    @Test
    void candidateWithoutSignalsIsMarkedAsColdStart() {
        FeedService service = service();
        PostSummary post = post(8L, "SCI_FI", null, List.of(), 0, List.of(), Instant.now().minus(Duration.ofDays(30)));

        var item = service.feedFromPosts(7L, List.of(post), null, 10, "home").records().getFirst();

        assertThat(item.recallSources()).containsExactly("cold_start");
    }

    @Test
    void followGraphOutageFailsStrictModeButCanUseExplicitDevelopmentFallback() {
        when(follows.followedAuthorIds(7L)).thenThrow(new IllegalStateException("graph unavailable"));
        FeedService service = service();
        PostSummary post = post(8L, "DEDUCTION", 701L, List.of(), 0, List.of(), Instant.now());

        assertThatThrownBy(() -> service.feedFromPosts(7L, List.of(post), null, 10, "home"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.code()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);
                    assertThat(error.getMessage()).isEqualTo("关注图谱暂时不可用");
                    assertThat(error.getCause()).isInstanceOf(IllegalStateException.class);
                });

        ReflectionTestUtils.setField(service, "readFallbackEnabled", true);
        assertThat(service.feedFromPosts(7L, List.of(post), null, 10, "home").records()).hasSize(1);
    }

    @Test
    void recommendationEventsNormalizeUntrustedDimensionsBeforePersistence() {
        FeedService service = service();

        Map<String, Object> result = service.recordRecommendEvent(
                7L, 11L, "UNKNOWN_EVENT", "UNKNOWN_SCENE", " ", null, "event-contract-key");

        assertThat(result).containsEntry("eventType", "detail_view")
                .containsEntry("scene", "mixed")
                .containsEntry("experimentId", "exp-theme-feed-v1")
                .containsEntry("bucket", "control");
        assertThat(result.get("eventId")).isInstanceOf(String.class);
        verify(impressions).saveRecommendEvent(
                7L, 11L, "detail_view", "mixed", "exp-theme-feed-v1", "control", "event-contract-key");
        verify(hashes).increment("recommend:event:mixed", "detail_view", 1);
    }

    private FeedService service() {
        return new FeedService(new RecommendationScorer(), redis, impressions,
                "http://localhost:8103", follows);
    }

    private PostSummary post(Long id, String theme, Long authorId, List<String> tags,
                             long likes, List<Long> relatedIds, Instant createdAt) {
        return new PostSummary(id, "TEXT", theme, "CASE_FILE", "MEDIUM", false, 0,
                "测试内容 " + id, "摘要", "author", authorId, tags, List.of(), likes, 0, 0,
                "PUBLISHED", createdAt, List.of(), List.of(), List.of(), "", List.of(), "",
                List.of(), List.of(), relatedIds);
    }
}
