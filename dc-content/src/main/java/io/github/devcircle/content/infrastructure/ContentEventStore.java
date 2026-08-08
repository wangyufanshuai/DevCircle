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

    public void append(String topic, String aggregateType, String aggregateId, Object payload) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> eventPayload = payload instanceof Map<?, ?> map
                    ? (Map<String, Object>) map
                    : objectMapper.convertValue(payload, Map.class);
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
}
