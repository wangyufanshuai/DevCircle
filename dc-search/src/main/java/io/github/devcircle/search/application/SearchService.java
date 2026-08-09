package io.github.devcircle.search.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.api.PageResponse;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.content.contract.PostSummary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.List;

/** Public search use-case facade; query and projection lifecycles are owned separately. */
@Service
public class SearchService {
    private final SearchQueryService queryService;
    private final SearchIndexService indexService;

    @Autowired
    SearchService(SearchQueryService queryService, SearchIndexService indexService) {
        this.queryService = queryService;
        this.indexService = indexService;
    }

    SearchService(ElasticsearchOperations elasticsearchOperations,
                  ObjectMapper objectMapper, String contentBaseUrl,
                  SearchEventCoordinator eventCoordinator) {
        this(new SearchQueryService(elasticsearchOperations, objectMapper),
                new SearchIndexService(elasticsearchOperations, objectMapper, contentBaseUrl, eventCoordinator));
    }

    public PageResponse<PostSummary> search(String keyword, String tag, String sort) {
        return search(keyword, tag, sort, null, null, null);
    }

    public PageResponse<PostSummary> search(String keyword, String tag, String sort,
                                            String theme, String contentKind, String difficulty) {
        return queryService.search(keyword, tag, sort, theme, contentKind, difficulty);
    }

    public SearchSlice searchCursor(String keyword, String tag, String sort, String theme,
                                    String contentKind, String difficulty, String cursor, int requestedSize) {
        return queryService.searchCursor(keyword, tag, sort, theme, contentKind, difficulty, cursor, requestedSize);
    }

    public int reindexSeedPosts() {
        return indexService.reindexSeedPosts();
    }

    @KafkaListener(topics = {EventTopics.POST_CREATED, EventTopics.POST_UPDATED, EventTopics.AUDIT_COMPLETED,
            EventTopics.CONTENT_REJECTED}, groupId = "dc-search")
    public void indexPostEvent(String message) throws Exception {
        indexService.upsertPostFromEvent(message);
    }

    public void upsertPostFromEvent(String message) throws Exception {
        indexService.upsertPostFromEvent(message);
    }

    @KafkaListener(topics = EventTopics.POST_DELETED, groupId = "dc-search")
    public void deletePostEvent(String message) throws Exception {
        indexService.deletePostEvent(message);
    }

    void enableReadFallbackForTests() {
        queryService.enableReadFallbackForTests();
        indexService.enableReadFallbackForTests();
    }

    public record SearchSlice(List<PostSummary> records, String nextCursor, boolean hasMore) {
    }
}
