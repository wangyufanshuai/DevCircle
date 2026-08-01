package io.github.devcircle.common.event;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * A structurally valid version-one event envelope.
 *
 * Consumers should only inspect business payloads after an envelope has been
 * converted to this type. This prevents malformed or unsupported events from
 * being interpreted with partial defaults.
 */
public record ValidatedEvent(UUID eventId,
                             String type,
                             int version,
                             Instant occurredAt,
                             String producer,
                             String aggregateId,
                             JsonNode payload) {
}
