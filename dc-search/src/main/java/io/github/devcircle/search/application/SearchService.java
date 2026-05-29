package io.github.devcircle.search.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.common.api.PageResponse;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.common.model.PostSummary;
import io.github.devcircle.common.seed.ThemeContentLibrary;
import io.github.devcircle.search.infrastructure.PostSearchDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

@Service
public class SearchService {
    private final ElasticsearchOperations elasticsearchOperations;
    private final ObjectMapper objectMapper;
    private final RestClient contentClient;

    public SearchService(ElasticsearchOperations elasticsearchOperations,
                         ObjectMapper objectMapper,
                         @Value("${devcircle.content.base-url:http://localhost:8103}") String contentBaseUrl) {
        this.elasticsearchOperations = elasticsearchOperations;
        this.objectMapper = objectMapper;
        this.contentClient = RestClient.builder().baseUrl(contentBaseUrl).build();
    }

    public PageResponse<PostSummary> search(String keyword, String tag, String sort) {
        return search(keyword, tag, sort, null, null, null);
    }

    public PageResponse<PostSummary> search(String keyword, String tag, String sort, String theme, String contentKind, String difficulty) {
        try {
            var query = NativeQuery.builder()
                    .withQuery(q -> q.bool(b -> {
                        if (keyword != null && !keyword.isBlank()) {
                            b.must(m -> m.multiMatch(mm -> mm.fields("title", "excerpt").query(keyword)));
                        }
                        if (tag != null && !tag.isBlank()) {
                            b.filter(f -> f.term(t -> t.field("tags").value(tag)));
                        }
                        if ((keyword == null || keyword.isBlank()) && (tag == null || tag.isBlank())) {
                            b.must(m -> m.matchAll(ma -> ma));
                        }
                        return b;
                    }))
                    .withPageable(PageRequest.of(0, 20))
                    .build();
            List<PostSummary> records = elasticsearchOperations.search(query, PostSearchDocument.class).stream()
                    .map(SearchHit::getContent)
                    .map(this::toSummary)
                    .filter(post -> theme == null || theme.isBlank() || post.theme().equalsIgnoreCase(theme))
                    .filter(post -> contentKind == null || contentKind.isBlank() || post.contentKind().equalsIgnoreCase(contentKind))
                    .filter(post -> difficulty == null || difficulty.isBlank() || post.difficulty().equalsIgnoreCase(difficulty))
                    .toList();
            return PageResponse.of(records, records.size(), 1, 20);
        } catch (RuntimeException ex) {
            List<PostSummary> records = ThemeContentLibrary.search(keyword, theme, contentKind, difficulty).stream().limit(20).toList();
            return PageResponse.of(records, records.size(), 1, 20);
        }
    }

    public int reindexSeedPosts() {
        List<PostSummary> posts = loadPostsFromContent();
        if (posts.isEmpty()) {
            posts = ThemeContentLibrary.summaries();
        }
        try {
            elasticsearchOperations.save(posts.stream()
                    .map(post -> new PostSearchDocument(post.id(), post.title(), post.excerpt(), post.author(), post.tags(), post.status(), post.createdAt()))
                    .toList());
            elasticsearchOperations.indexOps(PostSearchDocument.class).refresh();
            return posts.size();
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    @KafkaListener(topics = {EventTopics.POST_CREATED, EventTopics.POST_UPDATED}, groupId = "dc-search")
    public void indexPostEvent(String payload) {
        upsertPostFromEvent(payload);
    }

    public void upsertPostFromEvent(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            Long postId = root.path("postId").asLong();
            String title = root.path("title").asText();
            List<String> tags = objectMapper.convertValue(root.path("tags"), objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            elasticsearchOperations.save(new PostSearchDocument(postId, title, title, "event", tags, "PENDING", Instant.now()));
            elasticsearchOperations.indexOps(PostSearchDocument.class).refresh();
        } catch (RuntimeException | java.io.IOException ignored) {
            // Failed events are replayable from Kafka or the manual reindex endpoint.
        }
    }

    @KafkaListener(topics = EventTopics.POST_DELETED, groupId = "dc-search")
    public void deletePostEvent(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            elasticsearchOperations.delete(root.path("postId").asText(), PostSearchDocument.class);
        } catch (RuntimeException | java.io.IOException ignored) {
        }
    }

    private List<PostSummary> loadPostsFromContent() {
        try {
            ApiResponse<PageResponse<PostSummary>> response = contentClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/api/posts")
                            .queryParam("page", 1)
                            .queryParam("size", 500)
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (response != null && response.data() != null) {
                return response.data().records();
            }
        } catch (RuntimeException ignored) {
        }
        return List.of();
    }

    private PostSummary toSummary(PostSearchDocument document) {
        return new PostSummary(document.getId(), "TEXT", inferTheme(document.getTags()), inferContentKind(document.getTags()), "MEDIUM", false, 0,
                document.getTitle(), document.getExcerpt(), document.getAuthor(), document.getTags(), List.of(), 0, 0, 0, document.getStatus(), document.getCreatedAt());
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
}
