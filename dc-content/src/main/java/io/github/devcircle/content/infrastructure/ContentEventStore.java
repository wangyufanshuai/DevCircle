package io.github.devcircle.content.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.event.EventEnvelope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Map;

@Repository
public class ContentEventStore {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public ContentEventStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcClient = JdbcClient.create(jdbcTemplate);
        this.objectMapper = objectMapper;
    }

    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void append(String topic, String aggregateType, String aggregateId, Object payload) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> eventPayload = payload instanceof Map<?, ?> map
                    ? (Map<String, Object>) map
                    : objectMapper.convertValue(payload, Map.class);
            if (java.util.Set.of("post.created", "post.updated", "post.deleted").contains(topic)) {
                int changed = jdbcClient.sql("UPDATE post SET version=version+1 WHERE id=:id")
                        .param("id", Long.parseLong(aggregateId)).update();
                if (changed != 1) throw new IllegalArgumentException("Versioned post event requires a retained post row");
                eventPayload = new java.util.LinkedHashMap<>(eventPayload);
                eventPayload.putAll(searchSnapshot(Long.parseLong(aggregateId)));
                if ("post.deleted".equals(topic) && !"DELETED".equals(eventPayload.get("status"))) {
                    throw new IllegalArgumentException("Deleted search event requires a retained DELETED post row");
                }
            }
            EventEnvelope event = EventEnvelope.now(topic, "dc-content", aggregateId, eventPayload);
            jdbcClient.sql("""
                            INSERT INTO outbox_event(event_id, topic, aggregate_type, aggregate_id, payload)
                            VALUES (:eventId, :topic, :aggregateType, :aggregateId, CAST(:payload AS JSON))
                            """)
                    .param("eventId", event.eventId().toString())
                    .param("topic", event.type())
                    .param("aggregateType", aggregateType)
                    .param("aggregateId", event.aggregateId())
                    .param("payload", objectMapper.writeValueAsString(event))
                    .update();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize outbox payload", ex);
        }
    }
    private Map<String, Object> searchSnapshot(long postId) {
        Map<String, Object> snapshot = jdbcClient.sql("""
                SELECT p.version, p.title, p.status, p.type, p.theme, p.content_kind, p.difficulty,
                       p.author_id, p.created_at, LEFT(pc.markdown, 140) AS excerpt
                FROM post p JOIN post_content pc ON pc.post_id=p.id WHERE p.id=:id FOR UPDATE
                """).param("id", postId).query((rs, row) -> {
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("postId", postId);
            result.put("aggregateVersion", rs.getLong("version"));
            result.put("title", rs.getString("title"));
            result.put("status", rs.getString("status"));
            result.put("type", rs.getString("type"));
            result.put("theme", rs.getString("theme"));
            result.put("contentKind", rs.getString("content_kind"));
            result.put("difficulty", rs.getString("difficulty"));
            result.put("excerpt", rs.getString("excerpt"));
            result.put("authorId", rs.getLong("author_id"));
            result.put("author", "user-" + rs.getLong("author_id"));
            result.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
            return result;
        }).single();
        snapshot.put("tags", jdbcClient.sql("SELECT t.name FROM tag t JOIN post_tag pt ON pt.tag_id=t.id WHERE pt.post_id=:id ORDER BY t.name FOR UPDATE")
                .param("id", postId).query(String.class).list());
        return snapshot;
    }

}
