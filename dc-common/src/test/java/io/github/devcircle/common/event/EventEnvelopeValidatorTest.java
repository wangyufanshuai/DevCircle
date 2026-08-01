package io.github.devcircle.common.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventEnvelopeValidatorTest {
    private static final String EVENT_ID = "c0a80121-8b5f-4ca2-9cd4-b243cce67b53";
    private static final Map<String, Set<String>> CONTRACT =
            Map.of("interaction.changed", Set.of("dc-interaction"));
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final EventEnvelopeValidator validator = new EventEnvelopeValidator(objectMapper);

    @Test
    void parsesACompleteVersionOneEnvelope() {
        ValidatedEvent event = validator.parse(envelope(root -> { }), CONTRACT);

        assertThat(event.eventId()).isEqualTo(UUID.fromString(EVENT_ID));
        assertThat(event.type()).isEqualTo("interaction.changed");
        assertThat(event.version()).isEqualTo(1);
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-08-01T10:15:30Z"));
        assertThat(event.producer()).isEqualTo("dc-interaction");
        assertThat(event.aggregateId()).isEqualTo("9001");
        assertThat(event.payload().path("postId").asLong()).isEqualTo(9001L);
    }

    @Test
    void producerSerializationAlwaysUsesTheIsoInstantWireFormat() throws Exception {
        EventEnvelope envelope = new EventEnvelope(UUID.fromString(EVENT_ID), "interaction.changed", 1,
                Instant.parse("2026-08-01T10:15:30Z"), "dc-interaction", "9001",
                Map.of("postId", 9001L));

        String message = objectMapper.writeValueAsString(envelope);

        assertThat(objectMapper.readTree(message).path("occurredAt").isTextual()).isTrue();
        assertThat(validator.parse(message, CONTRACT).occurredAt())
                .isEqualTo(Instant.parse("2026-08-01T10:15:30Z"));
    }

    @Test
    void rejectsMalformedAndUnsupportedIdentityFields() {
        assertThatThrownBy(() -> validator.parse(envelope(root -> root.put("eventId", "evt-1")), CONTRACT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("canonical UUID");
        assertThatThrownBy(() -> validator.parse(envelope(root -> root.put("version", 2)), CONTRACT))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Unsupported event version: 2");
        assertThatThrownBy(() -> validator.parse(envelope(root -> root.put("producer", "dc-user")), CONTRACT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported event producer dc-user for type interaction.changed");
        assertThatThrownBy(() -> validator.parse(envelope(root -> root.put("type", "follow.changed")), CONTRACT))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Unsupported event type: follow.changed");
    }

    @Test
    void rejectsMissingTemporalAggregateAndObjectPayloadFields() {
        assertThatThrownBy(() -> validator.parse(envelope(root -> root.put("occurredAt", "yesterday")), CONTRACT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ISO-8601");
        assertThatThrownBy(() -> validator.parse(envelope(root -> root.put("aggregateId", " ")), CONTRACT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must not be blank");
        assertThatThrownBy(() -> validator.parse(envelope(root -> root.putArray("payload")), CONTRACT))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Event payload must be a JSON object");
    }

    @Test
    void rejectsEmptyMalformedAndNonObjectMessages() {
        assertThatThrownBy(() -> validator.parse(null, CONTRACT))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Event envelope is empty");
        assertThatThrownBy(() -> validator.parse(" ", CONTRACT))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Event envelope is empty");
        assertThatThrownBy(() -> validator.parse("{", CONTRACT))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Event envelope is not valid JSON");
        assertThatThrownBy(() -> validator.parse("[]", CONTRACT))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Event envelope must be a JSON object");
    }

    @Test
    void rejectsMissingNonIntegralAndOutOfRangeVersions() {
        assertThatThrownBy(() -> validator.parse(envelope(root -> root.remove("version")), CONTRACT))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Event version must be an integer");
        assertThatThrownBy(() -> validator.parse(envelope(root -> root.put("version", "1")), CONTRACT))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Event version must be an integer");
        assertThatThrownBy(() -> validator.parse(envelope(root -> root.put("version", Long.MAX_VALUE)), CONTRACT))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Event version must be an integer");
    }

    @Test
    void rejectsInvalidContractDefinitions() {
        assertThatThrownBy(() -> validator.parse(envelope(root -> { }), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Allowed event contracts must not be empty");
        assertThatThrownBy(() -> validator.parse(envelope(root -> { }), Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Allowed event contracts must not be empty");

        Map<String, Set<String>> nullType = new HashMap<>();
        nullType.put(null, Set.of("dc-interaction"));
        assertThatThrownBy(() -> validator.parse(envelope(root -> { }), nullType))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Allowed event type must not be blank");
        assertThatThrownBy(() -> validator.parse(envelope(root -> { }), Map.of(" ", Set.of("dc-interaction"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Allowed event type must not be blank");

        Map<String, Set<String>> nullProducers = new HashMap<>();
        nullProducers.put("interaction.changed", null);
        assertThatThrownBy(() -> validator.parse(envelope(root -> { }), nullProducers))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Allowed producers for interaction.changed must not be empty");
        assertThatThrownBy(() -> validator.parse(envelope(root -> { }), Map.of("interaction.changed", Set.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Allowed producers for interaction.changed must not be empty");

        Set<String> producerWithNull = new HashSet<>();
        producerWithNull.add(null);
        assertThatThrownBy(() -> validator.parse(
                envelope(root -> { }), Map.of("interaction.changed", producerWithNull)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Allowed producers for interaction.changed must not be empty");
        assertThatThrownBy(() -> validator.parse(
                envelope(root -> { }), Map.of("interaction.changed", Set.of(" "))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Allowed producers for interaction.changed must not be empty");
    }

    @Test
    void rejectsMissingTypedWhitespaceAndOversizedTextFields() {
        assertThatThrownBy(() -> validator.parse(envelope(root -> root.remove("eventId")), CONTRACT))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Event eventId must be a string");
        assertThatThrownBy(() -> validator.parse(envelope(root -> root.put("eventId", 7)), CONTRACT))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Event eventId must be a string");
        assertThatThrownBy(() -> validator.parse(envelope(root -> root.put("type", " interaction.changed")), CONTRACT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Event type must not contain surrounding whitespace");
        assertThatThrownBy(() -> validator.parse(envelope(root -> root.put("type", "x".repeat(129))), CONTRACT))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Event type is too long");
        assertThatThrownBy(() -> validator.parse(envelope(root -> root.remove("payload")), CONTRACT))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Event payload must be a JSON object");
        assertThatThrownBy(() -> validator.parse(envelope(root -> root.put("eventId", "1-1-1-1-1")), CONTRACT))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Event eventId must be a canonical UUID");
    }

    private String envelope(Consumer<ObjectNode> customize) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("eventId", EVENT_ID);
        root.put("type", "interaction.changed");
        root.put("version", 1);
        root.put("occurredAt", "2026-08-01T10:15:30Z");
        root.put("producer", "dc-interaction");
        root.put("aggregateId", "9001");
        ObjectNode payload = root.putObject("payload");
        payload.put("actorId", 7);
        payload.put("postId", 9001);
        payload.put("interactionType", "LIKE");
        customize.accept(root);
        return root.toString();
    }
}
