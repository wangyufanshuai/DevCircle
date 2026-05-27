package io.github.devcircle.search.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.query.Query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ThemeSearchFallbackTest {
    @Test
    void fallbackSearchFiltersByThemeKindAndDifficulty() {
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        when(operations.search(any(Query.class), any(Class.class))).thenThrow(new IllegalStateException("offline"));
        SearchService service = new SearchService(operations, new ObjectMapper());

        var result = service.search("钟楼", null, "relevance", "DEDUCTION", "CASE_FILE", "EASY");

        assertThat(result.records()).isNotEmpty();
        assertThat(result.records()).allSatisfy(post -> {
            assertThat(post.theme()).isEqualTo("DEDUCTION");
            assertThat(post.contentKind()).isEqualTo("CASE_FILE");
            assertThat(post.difficulty()).isEqualTo("EASY");
        });
    }
}
