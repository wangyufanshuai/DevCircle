package io.github.devcircle.search.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.common.api.PageResponse;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.common.event.EventEnvelopeValidator;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.common.event.ValidatedEvent;
import io.github.devcircle.common.http.BoundedRestClientFactory;
import io.github.devcircle.content.contract.PostSummary;
import io.github.devcircle.seed.ThemeContentLibrary;
import io.github.devcircle.search.infrastructure.PostSearchDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.core.ParameterizedTypeReference;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Owns content hydration, full reindex and Kafka-driven search projection updates. */
@Service
final class SearchIndexService {
    private static final Map<String, Set<String>> UPSERT_EVENT_CONTRACTS = Map.of(
            EventTopics.POST_CREATED, Set.of("dc-content"),
            EventTopics.POST_UPDATED, Set.of("dc-content"),
            EventTopics.AUDIT_COMPLETED, Set.of("dc-admin"),
            EventTopics.CONTENT_REJECTED, Set.of("dc-admin"));
    private static final Map<String, Set<String>> DELETE_EVENT_CONTRACT =
            Map.of(EventTopics.POST_DELETED, Set.of("dc-content"));

    private final ObjectMapper objectMapper;
    private final EventEnvelopeValidator envelopeValidator;
    private final RestClient contentClient;
    private final SearchEventCoordinator eventCoordinator;
    private final SearchProjectionStore projectionStore;
    private boolean readFallbackEnabled;

    @Autowired
    SearchIndexService(ObjectMapper objectMapper,
                       @Value("${devcircle.content.base-url:http://localhost:8103}") String contentBaseUrl,
                       SearchEventCoordinator eventCoordinator,
                       SearchProjectionStore projectionStore,
                       @Value("${devcircle.search.read-fallback-enabled:false}") boolean readFallbackEnabled) {
        this.objectMapper = objectMapper;
        this.envelopeValidator = new EventEnvelopeValidator(objectMapper);
        this.contentClient = BoundedRestClientFactory.create(contentBaseUrl);
        this.eventCoordinator = eventCoordinator;
        this.projectionStore = projectionStore;
        this.readFallbackEnabled = readFallbackEnabled;
    }

    SearchIndexService(ElasticsearchOperations operations,
                       ObjectMapper objectMapper, String contentBaseUrl,
                       SearchEventCoordinator eventCoordinator) {
        this(objectMapper, contentBaseUrl, eventCoordinator,
                new SearchProjectionStore(operations), false);
    }

    int reindexSeedPosts() {
        List<PostSummary> posts;
        try {
            posts = loadPostsFromContent();
        } catch (RuntimeException ex) {
            if (ex instanceof BusinessException) throw ex;
            throw dependency("内容服务暂时不可用，无法重建搜索索引", ex);
        }
        if (posts.isEmpty() && readFallbackEnabled) {
            posts = ThemeContentLibrary.summaries();
        }
        try {
            projectionStore.save(posts.stream()
                    .map(post -> document(post, post.createdAt()))
                    .toList());
            return posts.size();
        } catch (RuntimeException ex) {
            if (ex instanceof BusinessException) throw ex;
            throw dependency("搜索索引暂时不可用，重建未完成", ex);
        }
    }

    void upsertPostFromEvent(String message) throws Exception {
        ValidatedEvent event = envelopeValidator.parse(message, UPSERT_EVENT_CONTRACTS);
        String eventId = event.eventId().toString();
        String topic = event.type();
        JsonNode payload = event.payload();
        long postId = requiredPositiveLong(payload, "postId");
        if (!event.aggregateId().equals(Long.toString(postId))) {
            throw new IllegalArgumentException("Search aggregateId does not match postId");
        }
        Instant occurredAt = event.occurredAt();
        SearchEventCoordinator.ClaimResult claim = eventCoordinator.claim(event, postId);
        if (!claim.claimed()) {
            return;
        }
        try {
            String eventStatus = optionalText(payload, "status", "");
            if (EventTopics.CONTENT_REJECTED.equals(topic)
                    || (!eventStatus.isBlank() && !"PUBLISHED".equals(eventStatus))) {
                projectionStore.remove(postId, occurredAt, eventId);
                projectionStore.refresh();
                eventCoordinator.complete(eventId, postId, occurredAt, "REMOVE", claim.leaseToken());
                return;
            }
            PostSummary post;
            if ("PUBLISHED".equals(eventStatus) && payload.hasNonNull("title")) {
                List<String> tags = objectMapper.convertValue(payload.path("tags"),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                post = new PostSummary(postId, payload.path("type").asText("TEXT"), payload.path("theme").asText("DEDUCTION"),
                        payload.path("contentKind").asText("CASE_FILE"), payload.path("difficulty").asText("MEDIUM"),
                        payload.path("solvable").asBoolean(false), 0, payload.path("title").asText(),
                        payload.path("excerpt").asText(payload.path("title").asText()), payload.path("author").asText("event"),
                        payload.path("authorId").isNumber() ? payload.path("authorId").asLong() : null,
                        tags, List.of(), 0, 0, 0, "PUBLISHED", occurredAt);
            } else {
                post = loadPost(postId);
            }
            if (!"PUBLISHED".equals(post.status())) {
                projectionStore.remove(postId, occurredAt, eventId);
                projectionStore.refresh();
                eventCoordinator.complete(eventId, postId, occurredAt, "REMOVE", claim.leaseToken());
                return;
            }
            projectionStore.upsert(document(post, occurredAt, eventId));
            projectionStore.refresh();
            eventCoordinator.complete(eventId, postId, occurredAt, "UPSERT", claim.leaseToken());
        } catch (Exception ex) {
            try {
                eventCoordinator.fail(eventId, postId, ex, claim.leaseToken());
            } catch (RuntimeException stateFailure) {
                ex.addSuppressed(stateFailure);
            }
            throw ex;
        }
    }

    void deletePostEvent(String message) throws Exception {
        ValidatedEvent event = envelopeValidator.parse(message, DELETE_EVENT_CONTRACT);
        String eventId = event.eventId().toString();
        Instant occurredAt = event.occurredAt();
        long postId = requiredPositiveLong(event.payload(), "postId");
        if (!event.aggregateId().equals(Long.toString(postId))) {
            throw new IllegalArgumentException("Search aggregateId does not match postId");
        }
        SearchEventCoordinator.ClaimResult claim = eventCoordinator.claim(event, postId);
        if (!claim.claimed()) {
            return;
        }
        try {
            projectionStore.remove(postId, occurredAt, eventId);
            projectionStore.refresh();
            eventCoordinator.complete(eventId, postId, occurredAt, "REMOVE", claim.leaseToken());
        } catch (RuntimeException ex) {
            try {
                eventCoordinator.fail(eventId, postId, ex, claim.leaseToken());
            } catch (RuntimeException stateFailure) {
                ex.addSuppressed(stateFailure);
            }
            throw ex;
        }
    }

    void enableReadFallbackForTests() {
        readFallbackEnabled = true;
    }

    private List<PostSummary> loadPostsFromContent() {
        List<PostSummary> posts = new ArrayList<>();
        for (int page = 1; page <= 100; page++) {
            int currentPage = page;
            ApiResponse<PageResponse<PostSummary>> response = contentClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/api/posts")
                            .queryParam("page", currentPage)
                            .queryParam("size", 20)
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (response == null || response.data() == null || response.data().records() == null) {
                throw new IllegalStateException("Content service returned an invalid post page");
            }
            List<PostSummary> records = response.data().records();
            posts.addAll(records);
            if (records.size() < 20) {
                break;
            }
        }
        return posts;
    }

    private PostSummary loadPost(Long postId) throws Exception {
        String body = contentClient.get()
                .uri("/api/posts/{id}", postId)
                .retrieve()
                .body(String.class);
        JsonNode response = objectMapper.readTree(body);
        JsonNode data = response.path("data");
        if (!"OK".equals(response.path("code").asText()) || !data.isObject()) {
            throw new IllegalStateException("Content service returned no post for " + postId);
        }
        JsonNode authorNode = data.path("author");
        String author = authorNode.isObject() ? authorNode.path("username").asText("") : authorNode.asText("");
        Long authorId = authorNode.isObject() && authorNode.path("id").isNumber() ? authorNode.path("id").asLong() : null;
        List<String> tags = objectMapper.convertValue(data.path("tags"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        return new PostSummary(postId, data.path("type").asText("TEXT"), requiredText(data, "theme"),
                requiredText(data, "contentKind"), requiredText(data, "difficulty"), data.path("solvable").asBoolean(false),
                data.path("solvedCount").asLong(0), requiredText(data, "title"), data.path("excerpt").asText(""), author,
                authorId, tags, List.of(), data.path("likes").asLong(0), data.path("comments").asLong(0),
                data.path("views").asLong(0), requiredText(data, "status"), Instant.parse(requiredText(data, "createdAt")));
    }

    private long requiredPositiveLong(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw new IllegalArgumentException("Search payload " + field + " must be a positive integer");
        }
        return value.longValue();
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) throw new IllegalArgumentException("Search event is missing " + field);
        return value;
    }

    private String optionalText(JsonNode payload, String field, String defaultValue) {
        JsonNode value = payload.get(field);
        if (value == null || value.isNull()) return defaultValue;
        if (!value.isTextual()) throw new IllegalArgumentException("Search payload " + field + " must be a string");
        return value.textValue();
    }

    private PostSearchDocument document(PostSummary post, Instant occurredAt) {
        return document(post, occurredAt, "reindex-" + post.id());
    }

    private PostSearchDocument document(PostSummary post, Instant occurredAt, String eventId) {
        return new PostSearchDocument(post.id(), post.title(), post.excerpt(), post.author(), post.authorId(), post.tags(),
                post.status(), post.theme(), post.contentKind(), post.difficulty(), post.createdAt(), occurredAt,
                occurredAt == null ? null : occurredAt.toEpochMilli(), eventId);
    }

    private BusinessException dependency(String message, RuntimeException cause) {
        BusinessException failure = new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, message);
        failure.initCause(cause);
        return failure;
    }
}
