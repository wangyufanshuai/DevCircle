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
import java.util.ArrayList;

@Service
public class SearchService {
    private final ElasticsearchOperations elasticsearchOperations;
    private final ObjectMapper objectMapper;
    private final RestClient contentClient;

    @Value("${devcircle.search.read-fallback-enabled:false}")
    private boolean readFallbackEnabled;

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
                    .filter(post -> "PUBLISHED".equals(post.status()))
                    .filter(post -> theme == null || theme.isBlank() || post.theme().equalsIgnoreCase(theme))
                    .filter(post -> contentKind == null || contentKind.isBlank() || post.contentKind().equalsIgnoreCase(contentKind))
                    .filter(post -> difficulty == null || difficulty.isBlank() || post.difficulty().equalsIgnoreCase(difficulty))
                    .toList();
            return PageResponse.of(records, records.size(), 1, 20);
        } catch (RuntimeException ex) {
            if (!readFallbackEnabled) {
                throw ex;
            }
            List<PostSummary> records = ThemeContentLibrary.search(keyword, theme, contentKind, difficulty).stream().limit(20).toList();
            return PageResponse.of(records, records.size(), 1, 20);
        }
    }

    public int reindexSeedPosts() {
        List<PostSummary> posts = loadPostsFromContent();
        if (posts.isEmpty() && readFallbackEnabled) {
            posts = ThemeContentLibrary.summaries();
        }
        try {
            elasticsearchOperations.save(posts.stream()
                    .map(post -> new PostSearchDocument(post.id(), post.title(), post.excerpt(), post.author(), post.tags(), post.status(), post.createdAt()))
                    .toList());
            elasticsearchOperations.indexOps(PostSearchDocument.class).refresh();
            return posts.size();
        } catch (RuntimeException ex) {
            throw ex;
        }
    }

    @KafkaListener(topics = {EventTopics.POST_CREATED, EventTopics.POST_UPDATED, EventTopics.AUDIT_COMPLETED}, groupId = "dc-search")
    public void indexPostEvent(String message) throws Exception {
        upsertPostFromEvent(message);
    }

    public void upsertPostFromEvent(String message) throws Exception {
        JsonNode envelope = objectMapper.readTree(message);
        JsonNode payload = envelope.path("payload");
        long postId = payload.path("postId").asLong(0);
        if (postId <= 0) {
            throw new IllegalArgumentException("Post event is missing postId");
        }
        Instant occurredAt = Instant.parse(envelope.path("occurredAt").asText());
        PostSearchDocument existing = elasticsearchOperations.get(String.valueOf(postId), PostSearchDocument.class);
        if (existing != null && existing.getEventOccurredAt() != null && existing.getEventOccurredAt().isAfter(occurredAt)) {
            return;
        }
        PostSummary post;
        if ("PUBLISHED".equals(payload.path("status").asText()) && payload.hasNonNull("title")) {
            List<String> tags = objectMapper.convertValue(payload.path("tags"),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            post = new PostSummary(postId, payload.path("type").asText("TEXT"), payload.path("theme").asText("DEDUCTION"),
                    payload.path("contentKind").asText("CASE_FILE"), payload.path("difficulty").asText("MEDIUM"),
                    payload.path("solvable").asBoolean(false), 0, payload.path("title").asText(),
                    payload.path("excerpt").asText(payload.path("title").asText()), payload.path("author").asText("event"),
                    tags, List.of(), 0, 0, 0, "PUBLISHED", occurredAt);
        } else {
            post = loadPost(postId);
        }
        if (!"PUBLISHED".equals(post.status())) {
            elasticsearchOperations.delete(String.valueOf(postId), PostSearchDocument.class);
            return;
        }
        elasticsearchOperations.save(new PostSearchDocument(post.id(), post.title(), post.excerpt(), post.author(),
                post.tags(), post.status(), post.createdAt(), occurredAt));
        elasticsearchOperations.indexOps(PostSearchDocument.class).refresh();
    }

    @KafkaListener(topics = EventTopics.POST_DELETED, groupId = "dc-search")
    public void deletePostEvent(String message) throws Exception {
        JsonNode root = objectMapper.readTree(message).path("payload");
        long postId = root.path("postId").asLong(0);
        if (postId <= 0) {
            throw new IllegalArgumentException("Post delete event is missing postId");
        }
        elasticsearchOperations.delete(String.valueOf(postId), PostSearchDocument.class);
    }

    private List<PostSummary> loadPostsFromContent() {
        List<PostSummary> posts = new ArrayList<>();
        for (int page = 1; page <= 100; page++) {
            ApiResponse<PageResponse<PostSummary>> response = contentClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/api/posts")
                            .queryParam("page", 1)
                            .queryParam("size", 20)
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (response != null && response.data() != null) {
                List<PostSummary> records = response.data().records();
                posts.addAll(records);
                if (records.size() < 20) {
                    break;
                }
            }
        }
        return posts;
    }

    private PostSummary loadPost(Long postId) {
        ApiResponse<PostSummary> response = contentClient.get()
                .uri("/api/posts/{id}", postId)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        if (response == null || response.data() == null) {
            throw new IllegalStateException("Content service returned no post for " + postId);
        }
        return response.data();
    }

    private PostSummary toSummary(PostSearchDocument document) {
        return new PostSummary(document.getId(), "TEXT", inferTheme(document.getTags()), inferContentKind(document.getTags()), "MEDIUM", false, 0,
                document.getTitle(), document.getExcerpt(), document.getAuthor(), document.getTags(), List.of(), 0, 0, 0, document.getStatus(), document.getCreatedAt());
    }

    void enableReadFallbackForTests() {
        this.readFallbackEnabled = true;
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
