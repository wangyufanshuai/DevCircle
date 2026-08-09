package io.github.devcircle.search.application;

import io.github.devcircle.common.api.PageResponse;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.content.contract.PostSummary;
import io.github.devcircle.seed.ThemeContentLibrary;
import io.github.devcircle.search.infrastructure.PostSearchDocument;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Owns user-facing search queries, cursor binding and explicit read fallback. */
@Service
final class SearchQueryService {
    private final ElasticsearchOperations elasticsearchOperations;
    private final SearchCursorCodec cursorCodec;
    private boolean readFallbackEnabled;

    @Autowired
    SearchQueryService(ElasticsearchOperations elasticsearchOperations,
                       ObjectMapper objectMapper,
                       @Value("${devcircle.search.read-fallback-enabled:false}") boolean readFallbackEnabled) {
        this.elasticsearchOperations = elasticsearchOperations;
        this.cursorCodec = new SearchCursorCodec(objectMapper);
        this.readFallbackEnabled = readFallbackEnabled;
    }

    SearchQueryService(ElasticsearchOperations elasticsearchOperations, ObjectMapper objectMapper) {
        this(elasticsearchOperations, objectMapper, false);
    }

    PageResponse<PostSummary> search(String keyword, String tag, String sort,
                                     String theme, String contentKind, String difficulty) {
        SearchService.SearchSlice result = searchCursor(keyword, tag, sort, theme, contentKind, difficulty, null, 20);
        return PageResponse.of(result.records(), result.records().size(), 1, 20);
    }

    SearchService.SearchSlice searchCursor(String keyword, String tag, String sort, String theme,
                                            String contentKind, String difficulty, String cursor,
                                            int requestedSize) {
        int size = Math.max(1, Math.min(requestedSize, 50));
        String normalizedSort = normalizeSort(sort);
        String fingerprint = cursorCodec.fingerprint(keyword, tag, normalizedSort, theme, contentKind, difficulty);
        SearchCursorCodec.Position position = cursorCodec.decode(cursor, normalizedSort, fingerprint);
        try {
            var queryBuilder = org.springframework.data.elasticsearch.client.elc.NativeQuery.builder()
                    .withQuery(q -> q.bool(b -> {
                        if (keyword != null && !keyword.isBlank()) {
                            b.must(m -> m.multiMatch(mm -> mm.fields("title", "excerpt").query(keyword)));
                        }
                        if (tag != null && !tag.isBlank()) {
                            b.filter(f -> f.term(t -> t.field("tags").value(tag)));
                        }
                        b.filter(f -> f.term(t -> t.field("status").value("PUBLISHED")));
                        if (theme != null && !theme.isBlank()) b.filter(f -> f.term(t -> t.field("theme").value(theme.toUpperCase())));
                        if (contentKind != null && !contentKind.isBlank()) b.filter(f -> f.term(t -> t.field("contentKind").value(contentKind.toUpperCase())));
                        if (difficulty != null && !difficulty.isBlank()) b.filter(f -> f.term(t -> t.field("difficulty").value(difficulty.toUpperCase())));
                        if ((keyword == null || keyword.isBlank()) && (tag == null || tag.isBlank())) {
                            b.must(m -> m.matchAll(ma -> ma));
                        }
                        return b;
                    }))
                    .withPageable(PageRequest.of(0, size + 1))
                    .withSort(sortOptions(normalizedSort));
            if (position.source() == SearchCursorCodec.Source.ELASTICSEARCH && !position.values().isEmpty()) {
                queryBuilder.withSearchAfter(position.values());
            }
            var hits = elasticsearchOperations.search(queryBuilder.build(), PostSearchDocument.class);
            List<org.springframework.data.elasticsearch.core.SearchHit<PostSearchDocument>> pageHits = hits.getSearchHits();
            boolean hasMore = pageHits.size() > size;
            List<org.springframework.data.elasticsearch.core.SearchHit<PostSearchDocument>> visibleHits =
                    hasMore ? pageHits.subList(0, size) : pageHits;
            List<PostSummary> records = visibleHits.stream()
                    .map(org.springframework.data.elasticsearch.core.SearchHit::getContent)
                    .map(this::toSummary)
                    .toList();
            String nextCursor = hasMore
                    ? cursorCodec.encodeElasticsearch(normalizedSort, fingerprint, visibleHits.getLast().getSortValues())
                    : "";
            return new SearchService.SearchSlice(records, nextCursor, hasMore);
        } catch (RuntimeException ex) {
            if (ex instanceof BusinessException) throw ex;
            if (!readFallbackEnabled || position.source() == SearchCursorCodec.Source.ELASTICSEARCH) {
                throw dependency("搜索服务暂时不可用", ex);
            }
            List<PostSummary> matches = ThemeContentLibrary.search(keyword, theme, contentKind, difficulty);
            int from = Math.min(position.offset(), matches.size());
            int to = Math.min(from + size, matches.size());
            boolean hasMore = to < matches.size();
            return new SearchService.SearchSlice(List.copyOf(matches.subList(from, to)),
                    hasMore ? cursorCodec.encodeSeed(to, normalizedSort, fingerprint) : "", hasMore);
        }
    }

    void enableReadFallbackForTests() {
        readFallbackEnabled = true;
    }

    private String normalizeSort(String sort) {
        return "latest".equalsIgnoreCase(sort) ? "latest" : "relevance";
    }

    private List<SortOptions> sortOptions(String sort) {
        List<SortOptions> options = new ArrayList<>();
        if ("relevance".equals(sort)) {
            options.add(SortOptions.of(builder -> builder.score(score -> score.order(SortOrder.Desc))));
        }
        options.add(SortOptions.of(builder -> builder.field(field -> field.field("createdAt").order(SortOrder.Desc))));
        options.add(SortOptions.of(builder -> builder.field(field -> field.field("id").order(SortOrder.Desc))));
        return options;
    }

    private PostSummary toSummary(PostSearchDocument document) {
        String theme = document.getTheme() == null ? inferTheme(document.getTags()) : document.getTheme();
        String contentKind = document.getContentKind() == null ? inferContentKind(document.getTags()) : document.getContentKind();
        String difficulty = document.getDifficulty() == null ? "MEDIUM" : document.getDifficulty();
        return new PostSummary(document.getId(), "TEXT", theme, contentKind, difficulty, false, 0,
                document.getTitle(), document.getExcerpt(), document.getAuthor(), document.getAuthorId(),
                document.getTags(), List.of(), 0, 0, 0, document.getStatus(), document.getCreatedAt());
    }

    private String inferTheme(List<String> tags) {
        if (tags.stream().anyMatch(tag -> tag.contains("科幻") || tag.contains("世界观"))) return "SCI_FI";
        if (tags.stream().anyMatch(tag -> tag.contains("宇宙") || tag.contains("星云") || tag.contains("观测"))) return "COSMOS";
        return "DEDUCTION";
    }

    private String inferContentKind(List<String> tags) {
        if (tags.stream().anyMatch(tag -> tag.contains("世界观"))) return "WORLDBUILDING";
        if (tags.stream().anyMatch(tag -> tag.contains("宇宙") || tag.contains("星云") || tag.contains("观测"))) return "COSMIC_ARTICLE";
        return "CASE_FILE";
    }

    private BusinessException dependency(String message, RuntimeException cause) {
        BusinessException failure = new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, message);
        failure.initCause(cause);
        return failure;
    }
}
