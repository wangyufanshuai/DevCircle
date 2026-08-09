package io.github.devcircle.search.application;

import io.github.devcircle.search.infrastructure.PostSearchDocument;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Owns the Elasticsearch projection write semantics and monotonic tombstones. */
@Component
class SearchProjectionStore {
    private static final IndexCoordinates POST_INDEX = IndexCoordinates.of("devcircle-posts");
    private static final String MONOTONIC_UPSERT_SCRIPT = """
            long incoming = params.projectionVersion;
            long current = ctx._source.projectionVersion == null ? Long.MIN_VALUE : ctx._source.projectionVersion;
            String currentEventId = ctx._source.projectionEventId;
            boolean newer = incoming > current
                    || (incoming == current && (currentEventId == null
                        || params.projectionEventId.compareTo(currentEventId) > 0));
            if (newer) {
                ctx._source.putAll(params.document);
            }
            """;
    private static final String MONOTONIC_REMOVE_SCRIPT = """
            long incoming = params.projectionVersion;
            long current = ctx._source.projectionVersion == null ? Long.MIN_VALUE : ctx._source.projectionVersion;
            String currentEventId = ctx._source.projectionEventId;
            boolean newer = incoming > current
                    || (incoming == current && (currentEventId == null
                        || params.projectionEventId.compareTo(currentEventId) > 0));
            if (newer) {
                ctx._source.clear();
                ctx._source.putAll(params.tombstone);
            }
            """;

    private final ElasticsearchOperations operations;

    SearchProjectionStore(ElasticsearchOperations operations) {
        this.operations = operations;
    }

    void save(Iterable<PostSearchDocument> documents) {
        operations.save(documents);
        refresh();
    }

    void refresh() {
        operations.indexOps(PostSearchDocument.class).refresh();
    }

    void upsert(PostSearchDocument document) {
        if (document.getProjectionVersion() == null || document.getProjectionEventId() == null) {
            throw new IllegalArgumentException("Search projection document is missing its ordering metadata");
        }
        UpdateQuery query = UpdateQuery.builder(String.valueOf(document.getId()))
                .withScript(MONOTONIC_UPSERT_SCRIPT)
                .withLang("painless")
                .withParams(Map.of(
                        "projectionVersion", document.getProjectionVersion(),
                        "projectionEventId", document.getProjectionEventId(),
                        "document", source(document)))
                .withUpsert(Document.create())
                .withScriptedUpsert(true)
                .withRetryOnConflict(3)
                .build();
        operations.update(query, POST_INDEX);
    }

    void remove(long postId, Instant occurredAt, String eventId) {
        if (occurredAt == null || eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("Search removal is missing its ordering metadata");
        }
        Map<String, Object> tombstone = new LinkedHashMap<>();
        tombstone.put("id", postId);
        tombstone.put("status", "DELETED");
        tombstone.put("eventOccurredAt", occurredAt.toEpochMilli());
        tombstone.put("projectionVersion", occurredAt.toEpochMilli());
        tombstone.put("projectionEventId", eventId);
        UpdateQuery query = UpdateQuery.builder(String.valueOf(postId))
                .withScript(MONOTONIC_REMOVE_SCRIPT)
                .withLang("painless")
                .withParams(Map.of(
                        "projectionVersion", occurredAt.toEpochMilli(),
                        "projectionEventId", eventId,
                        "tombstone", tombstone))
                .withUpsert(Document.create())
                .withScriptedUpsert(true)
                .withRetryOnConflict(3)
                .build();
        operations.update(query, POST_INDEX);
    }

    private Map<String, Object> source(PostSearchDocument document) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("id", document.getId());
        source.put("title", document.getTitle());
        source.put("excerpt", document.getExcerpt());
        source.put("author", document.getAuthor());
        source.put("authorId", document.getAuthorId());
        source.put("tags", document.getTags());
        source.put("status", document.getStatus());
        source.put("theme", document.getTheme());
        source.put("contentKind", document.getContentKind());
        source.put("difficulty", document.getDifficulty());
        source.put("createdAt", document.getCreatedAt() == null ? null : document.getCreatedAt().toEpochMilli());
        source.put("eventOccurredAt", document.getEventOccurredAt() == null ? null : document.getEventOccurredAt().toEpochMilli());
        source.put("projectionVersion", document.getProjectionVersion());
        source.put("projectionEventId", document.getProjectionEventId());
        source.values().removeIf(java.util.Objects::isNull);
        return source;
    }
}
