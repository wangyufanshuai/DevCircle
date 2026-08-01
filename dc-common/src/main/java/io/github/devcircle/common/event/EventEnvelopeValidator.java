package io.github.devcircle.common.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Strict parser for the shared versioned event-envelope contract. */
public final class EventEnvelopeValidator {
    public static final int CURRENT_VERSION = 1;

    private final ObjectMapper objectMapper;

    public EventEnvelopeValidator(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public ValidatedEvent parse(String message, Map<String, Set<String>> allowedContracts) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Event envelope is empty");
        }
        Map<String, Set<String>> contracts = requireContracts(allowedContracts);

        final JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Event envelope is not valid JSON", ex);
        }
        if (envelope == null || !envelope.isObject()) {
            throw new IllegalArgumentException("Event envelope must be a JSON object");
        }

        String rawEventId = requiredText(envelope, "eventId", 36);
        UUID eventId = canonicalUuid(rawEventId);
        String type = requiredText(envelope, "type", 128);
        Set<String> allowedProducers = contracts.get(type);
        if (allowedProducers == null) {
            throw new IllegalArgumentException("Unsupported event type: " + type);
        }

        JsonNode versionNode = envelope.get("version");
        if (versionNode == null || !versionNode.isIntegralNumber() || !versionNode.canConvertToInt()) {
            throw new IllegalArgumentException("Event version must be an integer");
        }
        int version = versionNode.intValue();
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException("Unsupported event version: " + version);
        }

        String rawOccurredAt = requiredText(envelope, "occurredAt", 64);
        final Instant occurredAt;
        try {
            occurredAt = Instant.parse(rawOccurredAt);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Event occurredAt must be an ISO-8601 instant", ex);
        }

        String producer = requiredText(envelope, "producer", 128);
        if (!allowedProducers.contains(producer)) {
            throw new IllegalArgumentException("Unsupported event producer " + producer + " for type " + type);
        }
        String aggregateId = requiredText(envelope, "aggregateId", 255);

        JsonNode payload = envelope.get("payload");
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("Event payload must be a JSON object");
        }
        return new ValidatedEvent(eventId, type, version, occurredAt, producer, aggregateId, payload);
    }

    private Map<String, Set<String>> requireContracts(Map<String, Set<String>> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("Allowed event contracts must not be empty");
        }
        Map<String, Set<String>> copy = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalArgumentException("Allowed event type must not be blank");
            }
            Set<String> producers = entry.getValue();
            if (producers == null || producers.isEmpty()
                    || producers.stream().anyMatch(producer -> producer == null || producer.isBlank())) {
                throw new IllegalArgumentException("Allowed producers for " + entry.getKey() + " must not be empty");
            }
            copy.put(entry.getKey(), Set.copyOf(producers));
        }
        return Map.copyOf(copy);
    }

    private String requiredText(JsonNode envelope, String field, int maximumLength) {
        JsonNode node = envelope.get(field);
        if (node == null || !node.isTextual()) {
            throw new IllegalArgumentException("Event " + field + " must be a string");
        }
        String value = node.textValue();
        if (value.isBlank()) {
            throw new IllegalArgumentException("Event " + field + " must not be blank");
        }
        if (!value.equals(value.strip())) {
            throw new IllegalArgumentException("Event " + field + " must not contain surrounding whitespace");
        }
        if (value.length() > maximumLength) {
            throw new IllegalArgumentException("Event " + field + " is too long");
        }
        return value;
    }

    private UUID canonicalUuid(String value) {
        final UUID parsed;
        try {
            parsed = UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Event eventId must be a canonical UUID", ex);
        }
        if (!parsed.toString().equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("Event eventId must be a canonical UUID");
        }
        return parsed;
    }
}
