package io.github.devcircle.common.event;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Stable SHA-256 binding for the immutable parts of a validated event. */
public final class EventFingerprint {
    private EventFingerprint() {
    }

    public static String sha256(ValidatedEvent event) {
        Objects.requireNonNull(event, "event");
        StringBuilder canonical = new StringBuilder();
        append(canonical, event.type());
        append(canonical, event.version());
        append(canonical, event.occurredAt());
        append(canonical, event.producer());
        append(canonical, event.aggregateId());
        StringBuilder canonicalPayload = new StringBuilder();
        appendJson(canonicalPayload, event.payload());
        append(canonical, canonicalPayload);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static void append(StringBuilder canonical, Object value) {
        String text = String.valueOf(value);
        canonical.append(text.length()).append(':').append(text).append('|');
    }

    private static void appendJson(StringBuilder canonical, JsonNode node) {
        if (node == null || node.isNull()) {
            canonical.append('n');
            return;
        }
        if (node.isObject()) {
            canonical.append('{');
            List<String> fields = new ArrayList<>();
            node.fieldNames().forEachRemaining(fields::add);
            fields.sort(String::compareTo);
            for (String field : fields) {
                append(canonical, field);
                appendJson(canonical, node.get(field));
            }
            canonical.append('}');
            return;
        }
        if (node.isArray()) {
            canonical.append('[');
            node.forEach(value -> appendJson(canonical, value));
            canonical.append(']');
            return;
        }
        append(canonical, node.toString());
    }
}
