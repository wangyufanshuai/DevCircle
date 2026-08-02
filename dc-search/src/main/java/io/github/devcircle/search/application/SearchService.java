package io.github.devcircle.search.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.common.api.PageResponse;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.common.event.EventEnvelopeValidator;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.common.event.ValidatedEvent;
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
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SearchService {
    private static final Map<String, Set<String>> UPSERT_EVENT_CONTRACTS = Map.of(
            EventTopics.POST_CREATED, Set.of("dc-content"),
            EventTopics.POST_UPDATED, Set.of("dc-content"),
            EventTopics.AUDIT_COMPLETED, Set.of("dc-admin"),
            EventTopics.CONTENT_REJECTED, Set.of("dc-admin"));
    private static final Map<String, Set<String>> DELETE_EVENT_CONTRACT =
            Map.of(EventTopics.POST_DELETED, Set.of("dc-content"));

    private final ElasticsearchOperations elasticsearchOperations;
    private final ObjectMapper objectMapper;
    private final EventEnvelopeValidator envelopeValidator;
    private final RestClient contentClient;
    private final SearchEventCoordinator eventCoordinator;

    @Value("${devcircle.search.read-fallback-enabled:false}")
    private boolean readFallbackEnabled;

    public SearchService(ElasticsearchOperations elasticsearchOperations,
                         ObjectMapper objectMapper,
                         @Value("${devcircle.content.base-url:http://localhost:8103}") String contentBaseUrl,
                         SearchEventCoordinator eventCoordinator) {
        this.elasticsearchOperations = elasticsearchOperations;
        this.objectMapper = objectMapper;
        this.envelopeValidator = new EventEnvelopeValidator(objectMapper);
        this.contentClient = RestClient.builder().baseUrl(contentBaseUrl).build();
        this.eventCoordinator = eventCoordinator;
    }

    public PageResponse<PostSummary> search(String keyword, String tag, String sort) {
        return search(keyword, tag, sort, null, null, null);
    }

    public PageResponse<PostSummary> search(String keyword, String tag, String sort, String theme, String contentKind, String difficulty) {
        SearchSlice result = searchCursor(keyword, tag, sort, theme, contentKind, difficulty, null, 20);
        return PageResponse.of(result.records(), result.records().size(), 1, 20);
    }

    public SearchSlice searchCursor(String keyword, String tag, String sort, String theme, String contentKind,
                                    String difficulty, String cursor, int requestedSize) {
        int size = Math.max(1, Math.min(requestedSize, 50));
        String normalizedSort = normalizeSort(sort);
        String fingerprint = cursorFingerprint(keyword, tag, normalizedSort, theme, contentKind, difficulty);
        SearchCursor position = decodeCursor(cursor, normalizedSort, fingerprint);
        try {
            var queryBuilder = NativeQuery.builder()
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
            if (position.source() == CursorSource.ELASTICSEARCH && !position.values().isEmpty()) {
                queryBuilder.withSearchAfter(position.values());
            }
            var hits = elasticsearchOperations.search(queryBuilder.build(), PostSearchDocument.class);
            List<SearchHit<PostSearchDocument>> pageHits = hits.getSearchHits();
            boolean hasMore = pageHits.size() > size;
            List<SearchHit<PostSearchDocument>> visibleHits = hasMore ? pageHits.subList(0, size) : pageHits;
            List<PostSummary> records = visibleHits.stream()
                    .map(SearchHit::getContent)
                    .map(this::toSummary)
                    .toList();
            String nextCursor = hasMore
                    ? encodeElasticsearchCursor(normalizedSort, fingerprint, visibleHits.getLast().getSortValues())
                    : "";
            return new SearchSlice(records, nextCursor, hasMore);
        } catch (RuntimeException ex) {
            if (ex instanceof BusinessException) throw ex;
            if (!readFallbackEnabled || position.source() == CursorSource.ELASTICSEARCH) {
                throw dependency("搜索服务暂时不可用", ex);
            }
            List<PostSummary> matches = ThemeContentLibrary.search(keyword, theme, contentKind, difficulty);
            int from = Math.min(position.offset(), matches.size());
            int to = Math.min(from + size, matches.size());
            boolean hasMore = to < matches.size();
            return new SearchSlice(List.copyOf(matches.subList(from, to)),
                    hasMore ? encodeSeedCursor(to, normalizedSort, fingerprint) : "", hasMore);
        }
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

    private String cursorFingerprint(String keyword, String tag, String sort, String theme,
                                     String contentKind, String difficulty) {
        String canonical = String.join("\u001f", normalizeFilter(keyword), normalizeFilter(tag), sort,
                normalizeFilter(theme), normalizeFilter(contentKind), normalizeFilter(difficulty));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String normalizeFilter(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private SearchCursor decodeCursor(String cursor, String sort, String fingerprint) {
        if (cursor == null || cursor.isBlank()) return SearchCursor.first();
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            JsonNode root = objectMapper.readTree(decoded);
            if (root.path("version").asInt() != 2
                    || !sort.equals(root.path("sort").asText())
                    || !fingerprint.equals(root.path("fingerprint").asText())) {
                throw new IllegalArgumentException();
            }
            CursorSource source = CursorSource.valueOf(root.path("source").asText());
            if (source == CursorSource.SEED) {
                int offset = root.path("offset").asInt(-1);
                if (offset < 0) throw new IllegalArgumentException();
                return new SearchCursor(source, offset, List.of());
            }
            if (!root.path("values").isArray() || root.path("values").isEmpty()) {
                throw new IllegalArgumentException();
            }
            List<Object> values = objectMapper.convertValue(root.path("values"), new TypeReference<>() {});
            return new SearchCursor(source, 0, values);
        } catch (RuntimeException | JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "搜索游标无效或与当前筛选条件不匹配");
        }
    }

    private String encodeElasticsearchCursor(String sort, String fingerprint, List<Object> values) {
        return encodeCursor(new CursorPayload(2, CursorSource.ELASTICSEARCH.name(), sort, fingerprint,
                normalizeSortValues(values), null));
    }

    private String encodeSeedCursor(int offset, String sort, String fingerprint) {
        return encodeCursor(new CursorPayload(2, CursorSource.SEED.name(), sort, fingerprint, List.of(), offset));
    }

    private String encodeCursor(CursorPayload payload) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    objectMapper.writeValueAsBytes(payload));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to encode search cursor", ex);
        }
    }

    private List<Object> normalizeSortValues(List<Object> values) {
        return values.stream().map(value -> value instanceof TemporalAccessor ? value.toString() : value).toList();
    }

    public int reindexSeedPosts() {
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
            elasticsearchOperations.save(posts.stream()
                    .map(post -> document(post, post.createdAt()))
                    .toList());
            elasticsearchOperations.indexOps(PostSearchDocument.class).refresh();
            return posts.size();
        } catch (RuntimeException ex) {
            if (ex instanceof BusinessException) throw ex;
            throw dependency("搜索索引暂时不可用，重建未完成", ex);
        }
    }

    @KafkaListener(topics = {EventTopics.POST_CREATED, EventTopics.POST_UPDATED, EventTopics.AUDIT_COMPLETED,
            EventTopics.CONTENT_REJECTED}, groupId = "dc-search")
    public void indexPostEvent(String message) throws Exception {
        upsertPostFromEvent(message);
    }

    public void upsertPostFromEvent(String message) throws Exception {
        ValidatedEvent event = envelopeValidator.parse(message, UPSERT_EVENT_CONTRACTS);
        String eventId = event.eventId().toString();
        String topic = event.type();
        JsonNode payload = event.payload();
        long postId = requiredPositiveLong(payload, "postId");
        if (!event.aggregateId().equals(Long.toString(postId))) {
            throw new IllegalArgumentException("Search aggregateId does not match postId");
        }
        Instant occurredAt = event.occurredAt();
        if (!eventCoordinator.claim(event, postId)) {
            return;
        }
        try {
            String eventStatus = optionalText(payload, "status", "");
            if (EventTopics.CONTENT_REJECTED.equals(topic)
                    || (!eventStatus.isBlank() && !"PUBLISHED".equals(eventStatus))) {
                elasticsearchOperations.delete(String.valueOf(postId), PostSearchDocument.class);
                elasticsearchOperations.indexOps(PostSearchDocument.class).refresh();
                eventCoordinator.complete(eventId, postId, occurredAt, "REMOVE");
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
                elasticsearchOperations.delete(String.valueOf(postId), PostSearchDocument.class);
                elasticsearchOperations.indexOps(PostSearchDocument.class).refresh();
                eventCoordinator.complete(eventId, postId, occurredAt, "REMOVE");
                return;
            }
            elasticsearchOperations.save(document(post, occurredAt));
            elasticsearchOperations.indexOps(PostSearchDocument.class).refresh();
            eventCoordinator.complete(eventId, postId, occurredAt, "UPSERT");
        } catch (Exception ex) {
            try {
                eventCoordinator.fail(eventId, postId, ex);
            } catch (RuntimeException stateFailure) {
                ex.addSuppressed(stateFailure);
            }
            throw ex;
        }
    }

    @KafkaListener(topics = EventTopics.POST_DELETED, groupId = "dc-search")
    public void deletePostEvent(String message) throws Exception {
        ValidatedEvent event = envelopeValidator.parse(message, DELETE_EVENT_CONTRACT);
        String eventId = event.eventId().toString();
        String topic = event.type();
        Instant occurredAt = event.occurredAt();
        long postId = requiredPositiveLong(event.payload(), "postId");
        if (!event.aggregateId().equals(Long.toString(postId))) {
            throw new IllegalArgumentException("Search aggregateId does not match postId");
        }
        if (!eventCoordinator.claim(event, postId)) {
            return;
        }
        try {
            elasticsearchOperations.delete(String.valueOf(postId), PostSearchDocument.class);
            elasticsearchOperations.indexOps(PostSearchDocument.class).refresh();
            eventCoordinator.complete(eventId, postId, occurredAt, "REMOVE");
        } catch (RuntimeException ex) {
            try {
                eventCoordinator.fail(eventId, postId, ex);
            } catch (RuntimeException stateFailure) {
                ex.addSuppressed(stateFailure);
            }
            throw ex;
        }
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
        String author = authorNode.isObject()
                ? authorNode.path("username").asText("")
                : authorNode.asText("");
        Long authorId = authorNode.isObject() && authorNode.path("id").isNumber()
                ? authorNode.path("id").asLong()
                : null;
        List<String> tags = objectMapper.convertValue(data.path("tags"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        return new PostSummary(postId,
                data.path("type").asText("TEXT"),
                requiredText(data, "theme"),
                requiredText(data, "contentKind"),
                requiredText(data, "difficulty"),
                data.path("solvable").asBoolean(false),
                data.path("solvedCount").asLong(0),
                requiredText(data, "title"),
                data.path("excerpt").asText(""),
                author,
                authorId,
                tags,
                List.of(),
                data.path("likes").asLong(0),
                data.path("comments").asLong(0),
                data.path("views").asLong(0),
                requiredText(data, "status"),
                Instant.parse(requiredText(data, "createdAt")));
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Search event is missing " + field);
        }
        return value;
    }

    private long requiredPositiveLong(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw new IllegalArgumentException("Search payload " + field + " must be a positive integer");
        }
        return value.longValue();
    }

    private String optionalText(JsonNode payload, String field, String defaultValue) {
        JsonNode value = payload.get(field);
        if (value == null || value.isNull()) return defaultValue;
        if (!value.isTextual()) {
            throw new IllegalArgumentException("Search payload " + field + " must be a string");
        }
        return value.textValue();
    }

    private PostSummary toSummary(PostSearchDocument document) {
        String theme = document.getTheme() == null ? inferTheme(document.getTags()) : document.getTheme();
        String contentKind = document.getContentKind() == null ? inferContentKind(document.getTags()) : document.getContentKind();
        String difficulty = document.getDifficulty() == null ? "MEDIUM" : document.getDifficulty();
        return new PostSummary(document.getId(), "TEXT", theme, contentKind, difficulty, false, 0,
                document.getTitle(), document.getExcerpt(), document.getAuthor(), document.getAuthorId(),
                document.getTags(), List.of(), 0, 0, 0, document.getStatus(), document.getCreatedAt());
    }

    private PostSearchDocument document(PostSummary post, Instant occurredAt) {
        return new PostSearchDocument(post.id(), post.title(), post.excerpt(), post.author(), post.authorId(), post.tags(), post.status(),
                post.theme(), post.contentKind(), post.difficulty(), post.createdAt(), occurredAt);
    }

    private BusinessException dependency(String message, RuntimeException cause) {
        BusinessException failure = new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, message);
        failure.initCause(cause);
        return failure;
    }

    public record SearchSlice(List<PostSummary> records, String nextCursor, boolean hasMore) {
    }

    private enum CursorSource {
        FIRST, ELASTICSEARCH, SEED
    }

    private record SearchCursor(CursorSource source, int offset, List<Object> values) {
        private static SearchCursor first() {
            return new SearchCursor(CursorSource.FIRST, 0, List.of());
        }
    }

    private record CursorPayload(int version, String source, String sort, String fingerprint,
                                 List<Object> values, Integer offset) {
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
