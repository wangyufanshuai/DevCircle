package io.github.devcircle.search.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.search.infrastructure.PostSearchDocument;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Query;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchServiceCursorContractTest {
    private final ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
    private final SearchService service = new SearchService(operations,
            new ObjectMapper().findAndRegisterModules(), "http://localhost:8103",
            mock(SearchEventCoordinator.class));

    @Test
    void elasticsearchCursorContinuesWithoutRepeatingAndPreservesLegacyThemeInference() {
        Instant firstTime = Instant.parse("2026-08-02T09:00:00Z");
        Instant secondTime = Instant.parse("2026-08-02T08:00:00Z");
        PostSearchDocument scienceFiction = document(3L, "漂流城协议", List.of("科幻", "世界观"), firstTime);
        PostSearchDocument cosmos = document(2L, "星云回声", List.of("宇宙", "观测"), secondTime);
        PostSearchDocument deduction = new PostSearchDocument(1L, "钟楼记录", "午夜档案", "记录员", 7L,
                List.of("推理"), "PUBLISHED", "DEDUCTION", "CASE_FILE", "HARD",
                Instant.parse("2026-08-02T07:00:00Z"), Instant.parse("2026-08-02T07:00:00Z"));
        SearchHits<PostSearchDocument> firstPage = hits(
                hit(scienceFiction, firstTime, 3L),
                hit(cosmos, secondTime, 2L),
                hit(deduction, Instant.parse("2026-08-02T07:00:00Z"), 1L));
        SearchHits<PostSearchDocument> secondPage = hits();
        when(operations.search(any(Query.class), eq(PostSearchDocument.class)))
                .thenReturn(firstPage, secondPage);

        SearchService.SearchSlice first = service.searchCursor("", "", "latest",
                null, null, null, "", 2);
        SearchService.SearchSlice second = service.searchCursor("", "", "latest",
                null, null, null, first.nextCursor(), 2);

        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isNotBlank();
        assertThat(first.records()).extracting(post -> post.id()).containsExactly(3L, 2L);
        assertThat(first.records().get(0)).satisfies(post -> {
            assertThat(post.theme()).isEqualTo("SCI_FI");
            assertThat(post.contentKind()).isEqualTo("WORLDBUILDING");
            assertThat(post.difficulty()).isEqualTo("MEDIUM");
        });
        assertThat(first.records().get(1)).satisfies(post -> {
            assertThat(post.theme()).isEqualTo("COSMOS");
            assertThat(post.contentKind()).isEqualTo("COSMIC_ARTICLE");
        });
        assertThat(second.records()).isEmpty();
        assertThat(second.hasMore()).isFalse();
        assertThat(second.nextCursor()).isEmpty();
        verify(operations, times(2)).search(any(Query.class), eq(PostSearchDocument.class));
    }

    @Test
    void anElasticsearchCursorNeverSwitchesToSeedDataAfterASecondPageFailure() {
        Instant occurredAt = Instant.parse("2026-08-02T09:00:00Z");
        SearchHits<PostSearchDocument> firstPage = hits(
                hit(document(3L, "第一页", List.of("推理"), occurredAt), occurredAt, 3L),
                hit(document(2L, "下一页", List.of("推理"), occurredAt.minusSeconds(60)),
                        occurredAt.minusSeconds(60), 2L));
        IllegalStateException outage = new IllegalStateException("elasticsearch disconnected between pages");
        when(operations.search(any(Query.class), eq(PostSearchDocument.class)))
                .thenReturn(firstPage)
                .thenThrow(outage);
        service.enableReadFallbackForTests();

        SearchService.SearchSlice first = service.searchCursor("", null, "latest",
                null, null, null, null, 1);

        assertThatThrownBy(() -> service.searchCursor("", null, "latest",
                null, null, null, first.nextCursor(), 1))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.code()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);
                    assertThat(error.getCause()).isSameAs(outage);
                });
    }

    private PostSearchDocument document(long id, String title, List<String> tags, Instant createdAt) {
        return new PostSearchDocument(id, title, title + "摘要", "记录员", tags,
                "PUBLISHED", createdAt, createdAt);
    }

    @SafeVarargs
    private SearchHits<PostSearchDocument> hits(SearchHit<PostSearchDocument>... values) {
        @SuppressWarnings("unchecked")
        SearchHits<PostSearchDocument> hits = mock(SearchHits.class);
        when(hits.getSearchHits()).thenReturn(List.of(values));
        return hits;
    }

    private SearchHit<PostSearchDocument> hit(PostSearchDocument document, Object... sortValues) {
        @SuppressWarnings("unchecked")
        SearchHit<PostSearchDocument> hit = mock(SearchHit.class);
        when(hit.getContent()).thenReturn(document);
        when(hit.getSortValues()).thenReturn(Arrays.asList(sortValues));
        return hit;
    }
}
