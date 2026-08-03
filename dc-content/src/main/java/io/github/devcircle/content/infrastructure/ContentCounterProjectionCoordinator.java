package io.github.devcircle.content.infrastructure;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

@Component
class ContentCounterProjectionCoordinator {
    enum CounterType {
        COMMENT("comment_count"),
        LIKE("like_count");

        private final String column;

        CounterType(String column) {
            this.column = column;
        }
    }

    private final JdbcClient jdbcClient;

    ContentCounterProjectionCoordinator(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    boolean apply(CounterType counterType, long postId, Instant occurredAt, String eventId,
                  Long sourceVersion, Long absoluteCount, int legacyDelta) {
        if (sourceVersion != null && sourceVersion <= 0) {
            throw new IllegalArgumentException("Projected counter version must be positive");
        }
        if (absoluteCount != null && absoluteCount < 0) {
            throw new IllegalArgumentException("Projected counter cannot be negative");
        }
        jdbcClient.sql("""
                        INSERT IGNORE INTO content_counter_projection_watermark(counter_type, post_id)
                        VALUES (:counterType, :postId)
                        """)
                .param("counterType", counterType.name())
                .param("postId", postId)
                .update();

        CounterWatermark watermark = jdbcClient.sql("""
                        SELECT source_version, occurred_at, event_id
                        FROM content_counter_projection_watermark
                        WHERE counter_type = :counterType AND post_id = :postId
                        FOR UPDATE
                        """)
                .param("counterType", counterType.name())
                .param("postId", postId)
                .query((rs, rowNum) -> {
                    Timestamp timestamp = rs.getTimestamp("occurred_at");
                    return new CounterWatermark((Long) rs.getObject("source_version"),
                            timestamp == null ? null : timestamp.toInstant(),
                            rs.getString("event_id"));
                })
                .single();
        if (!isNewer(sourceVersion, occurredAt, eventId, watermark)) return false;

        String updateSql = absoluteCount == null
                ? "UPDATE post SET " + counterType.column + " = GREATEST(" + counterType.column
                    + " + :value, 0) WHERE id = :postId"
                : "UPDATE post SET " + counterType.column + " = :value WHERE id = :postId";
        long value = absoluteCount == null ? legacyDelta : absoluteCount;
        int updated = jdbcClient.sql(updateSql)
                .param("value", value)
                .param("postId", postId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Counter event references missing post " + postId);
        }

        int watermarkUpdated = jdbcClient.sql("""
                        UPDATE content_counter_projection_watermark
                        SET source_version = :sourceVersion, occurred_at = :occurredAt, event_id = :eventId
                        WHERE counter_type = :counterType AND post_id = :postId
                        """)
                .param("occurredAt", Timestamp.from(occurredAt))
                .param("sourceVersion", sourceVersion)
                .param("eventId", eventId)
                .param("counterType", counterType.name())
                .param("postId", postId)
                .update();
        if (watermarkUpdated != 1) {
            throw new IllegalStateException("Counter projection watermark disappeared for post " + postId);
        }
        return true;
    }

    private boolean isNewer(Long sourceVersion, Instant occurredAt, String eventId,
                            CounterWatermark watermark) {
        if (sourceVersion != null) {
            return watermark.sourceVersion() == null || sourceVersion > watermark.sourceVersion();
        }
        if (watermark.sourceVersion() != null) return false;
        if (watermark.occurredAt() == null) return true;
        int timeOrder = Long.compare(occurredAt.toEpochMilli(), watermark.occurredAt().toEpochMilli());
        if (timeOrder != 0) return timeOrder > 0;
        return watermark.eventId() == null || eventId.compareTo(watermark.eventId()) > 0;
    }

    private record CounterWatermark(Long sourceVersion, Instant occurredAt, String eventId) {
    }
}
