package io.github.devcircle.content.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.event.EventFingerprint;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.common.event.ValidatedEvent;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringJUnitConfig(ContentProjectionDedupTest.TestConfig.class)
class ContentProjectionDedupTest {
    private static final String COMMENT_EVENT_ID = "2fc9c5a7-f4c5-41d6-adf7-6f9ef5139e2a";
    private static final String INTERACTION_EVENT_ID = "ef66d1af-3558-4472-b67f-7ee3c90bec16";
    private static final String LEGACY_FINGERPRINT = "0".repeat(64);

    private final ObjectMapper objectMapper = new ObjectMapper();
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private ContentCommentProjectionRepository comments;
    @Autowired
    private ContentInteractionProjectionRepository interactions;

    @BeforeEach
    void setUp() {
        jdbcClient.sql("DROP ALL OBJECTS").update();
        createPostTable();
        jdbcClient.sql("INSERT INTO post(id) VALUES (1001)").update();
        jdbcClient.sql("""
                CREATE TABLE comment_projection_dedup(
                  event_id CHAR(36) PRIMARY KEY, topic VARCHAR(128) NOT NULL,
                  event_fingerprint CHAR(64) NOT NULL DEFAULT '0000000000000000000000000000000000000000000000000000000000000000')
                """).update();
        jdbcClient.sql("""
                CREATE TABLE interaction_projection_dedup(
                  event_id CHAR(36) PRIMARY KEY, topic VARCHAR(128) NOT NULL,
                  event_fingerprint CHAR(64) NOT NULL DEFAULT '0000000000000000000000000000000000000000000000000000000000000000')
                """).update();
        jdbcClient.sql("""
                CREATE TABLE content_counter_projection_watermark(
                  counter_type VARCHAR(16) NOT NULL,
                  post_id BIGINT NOT NULL,
                  source_version BIGINT,
                  occurred_at TIMESTAMP,
                  event_id CHAR(36),
                  PRIMARY KEY(counter_type, post_id))
                """).update();
    }

    @Test
    void commentEventIdCannotMoveBetweenLegacyAndOrderedTopics() {
        ValidatedEvent event = commentEvent(
                COMMENT_EVENT_ID, EventTopics.COMMENT_CREATED, 9L, 1001L, "CREATED");
        assertThat(comments.apply(event, 1001L, "CREATED")).isTrue();
        assertThat(comments.apply(event, 1001L, "CREATED")).isFalse();
        ValidatedEvent changedTopic = new ValidatedEvent(event.eventId(), EventTopics.COMMENT_CHANGED, 1,
                event.occurredAt(), event.producer(), event.aggregateId(), event.payload());

        assertThatThrownBy(() -> comments.apply(changedTopic, 1001L, "CREATED"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Event ID is already bound to topic comment.created");
        assertThat(commentCount()).isEqualTo(1L);
    }

    @Test
    void interactionEventIdCannotBeSilentlyReusedByAnotherTopic() {
        ValidatedEvent event = interactionEvent(INTERACTION_EVENT_ID, 1001L, "LIKE");
        assertThat(interactions.apply(event, 1001L, "LIKE")).isTrue();
        ValidatedEvent changedTopic = new ValidatedEvent(event.eventId(), EventTopics.FOLLOW_CHANGED, 1,
                event.occurredAt(), event.producer(), event.aggregateId(), event.payload());

        assertThatThrownBy(() -> interactions.apply(changedTopic, 1001L, "LIKE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Event ID is already bound to topic interaction.changed");
        assertThat(likeCount()).isEqualTo(1L);
    }

    @Test
    void projectionEventIdsCannotBeReplayedWithChangedPayloads() {
        ValidatedEvent comment = commentEvent(
                COMMENT_EVENT_ID, EventTopics.COMMENT_CHANGED, 9L, 1001L, "CREATED");
        ValidatedEvent interaction = interactionEvent(INTERACTION_EVENT_ID, 1001L, "LIKE");
        assertThat(comments.apply(comment, 1001L, "CREATED")).isTrue();
        assertThat(interactions.apply(interaction, 1001L, "LIKE")).isTrue();

        assertPayloadConflict(() -> comments.apply(commentEvent(
                COMMENT_EVENT_ID, EventTopics.COMMENT_CHANGED, 9L, 1001L, "HIDDEN"), 1001L, "HIDDEN"));
        assertPayloadConflict(() -> interactions.apply(
                interactionEvent(INTERACTION_EVENT_ID, 1001L, "UNLIKE"), 1001L, "UNLIKE"));
        assertPayloadConflict(() -> interactions.apply(new ValidatedEvent(
                interaction.eventId(), interaction.type(), 1, interaction.occurredAt().plusSeconds(1),
                interaction.producer(), interaction.aggregateId(), interaction.payload()), 1001L, "LIKE"));
        assertThat(commentCount()).isEqualTo(1L);
        assertThat(likeCount()).isEqualTo(1L);
    }

    @Test
    void legacyProjectionRecordsAreBoundWithoutApplyingCountersAgain() {
        ValidatedEvent comment = commentEvent(
                COMMENT_EVENT_ID, EventTopics.COMMENT_CHANGED, 9L, 1001L, "CREATED");
        ValidatedEvent interaction = interactionEvent(INTERACTION_EVENT_ID, 1001L, "LIKE");
        assertThat(comments.apply(comment, 1001L, "CREATED")).isTrue();
        assertThat(interactions.apply(interaction, 1001L, "LIKE")).isTrue();
        jdbcClient.sql("UPDATE comment_projection_dedup SET event_fingerprint = :legacy")
                .param("legacy", LEGACY_FINGERPRINT).update();
        jdbcClient.sql("UPDATE interaction_projection_dedup SET event_fingerprint = :legacy")
                .param("legacy", LEGACY_FINGERPRINT).update();

        ValidatedEvent commentReplay = commentEvent(
                COMMENT_EVENT_ID, EventTopics.COMMENT_CHANGED, 9L, 1001L, "HIDDEN");
        ValidatedEvent interactionReplay = interactionEvent(INTERACTION_EVENT_ID, 1001L, "UNLIKE");
        assertThat(comments.apply(commentReplay, 1001L, "HIDDEN")).isFalse();
        assertThat(interactions.apply(interactionReplay, 1001L, "UNLIKE")).isFalse();
        assertThat(commentCount()).isEqualTo(1L);
        assertThat(likeCount()).isEqualTo(1L);
        assertThat(fingerprint("comment_projection_dedup", COMMENT_EVENT_ID))
                .isEqualTo(EventFingerprint.sha256(commentReplay));
        assertThat(fingerprint("interaction_projection_dedup", INTERACTION_EVENT_ID))
                .isEqualTo(EventFingerprint.sha256(interactionReplay));
        assertPayloadConflict(() -> comments.apply(comment, 1001L, "CREATED"));
        assertPayloadConflict(() -> interactions.apply(interaction, 1001L, "LIKE"));
    }

    @Test
    void failedCounterWritesRollBackClaimsAndAllowOriginalEventsToRetry() {
        ValidatedEvent comment = commentEvent(
                COMMENT_EVENT_ID, EventTopics.COMMENT_CHANGED, 9L, 2001L, "CREATED");
        ValidatedEvent interaction = interactionEvent(INTERACTION_EVENT_ID, 2002L, "LIKE");
        assertThatThrownBy(() -> comments.apply(comment, 2001L, "CREATED"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> interactions.apply(interaction, 2002L, "LIKE"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM comment_projection_dedup")
                .query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM interaction_projection_dedup")
                .query(Integer.class).single()).isZero();

        jdbcClient.sql("INSERT INTO post(id) VALUES (2001), (2002)").update();
        assertThat(comments.apply(comment, 2001L, "CREATED")).isTrue();
        assertThat(interactions.apply(interaction, 2002L, "LIKE")).isTrue();
    }

    @Test
    void commentLifecycleBranchesUpdateCountsAndRejectUnsupportedTransitionsAtomically() {
        jdbcClient.sql("UPDATE post SET comment_count = 2 WHERE id = 1001").update();
        assertThat(comments.apply(commentEvent(
                "11111111-1111-4111-8111-111111111111",
                EventTopics.COMMENT_CHANGED, 10L, 1001L, "DELETED"), 1001L, "DELETED")).isTrue();
        assertThat(comments.apply(commentEvent(
                "22222222-2222-4222-8222-222222222222",
                EventTopics.COMMENT_CHANGED, 11L, 1001L, "HIDDEN"), 1001L, "HIDDEN")).isTrue();
        assertThat(comments.apply(commentEvent(
                "33333333-3333-4333-8333-333333333333",
                EventTopics.COMMENT_CHANGED, 12L, 1001L, "CREATED"), 1001L, "CREATED")).isTrue();
        assertThat(commentCount()).isEqualTo(1L);

        ValidatedEvent unsupported = commentEvent(
                "44444444-4444-4444-8444-444444444444",
                EventTopics.COMMENT_CREATED, 13L, 1001L, "HIDDEN");
        assertThatThrownBy(() -> comments.apply(unsupported, 1001L, "HIDDEN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported comment event: comment.created/HIDDEN");
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM comment_projection_dedup
                        WHERE event_id = '44444444-4444-4444-8444-444444444444'
                        """).query(Integer.class).single()).isZero();
    }

    @Test
    void interactionBranchesApplyLikesAndTreatFavoritesAsZeroDelta() {
        jdbcClient.sql("UPDATE post SET like_count = 2 WHERE id = 1001").update();
        assertThat(interactions.apply(interactionEvent(
                "55555555-5555-4555-8555-555555555555", 1001L, "UNLIKE"), 1001L, "UNLIKE")).isTrue();
        assertThat(interactions.apply(interactionEvent(
                "66666666-6666-4666-8666-666666666666", 1001L, "FAVORITE"), 1001L, "FAVORITE")).isTrue();
        assertThat(interactions.apply(interactionEvent(
                "77777777-7777-4777-8777-777777777777", 1001L, "UNFAVORITE"), 1001L, "UNFAVORITE")).isTrue();
        assertThat(likeCount()).isEqualTo(1L);

        ValidatedEvent unsupported = interactionEvent(
                "88888888-8888-4888-8888-888888888888", 1001L, "SHARE");
        assertThatThrownBy(() -> interactions.apply(unsupported, 1001L, "SHARE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported interaction type: SHARE");
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM interaction_projection_dedup
                        WHERE event_id = '88888888-8888-4888-8888-888888888888'
                        """).query(Integer.class).single()).isZero();
    }

    @Test
    void absoluteCounterSnapshotsConvergeUnderOutOfOrderAndSameMillisecondDelivery() {
        Instant first = Instant.parse("2026-08-01T11:00:00.000900Z");
        Instant sameMillisecondNewer = Instant.parse("2026-08-01T11:00:00.000100Z");
        Instant sameMillisecondOlder = Instant.parse("2026-08-01T11:00:00.000800Z");
        Instant older = Instant.parse("2026-08-01T10:59:59Z");

        assertThat(comments.apply(commentEvent(
                "66666666-1111-4111-8111-111111111111", EventTopics.COMMENT_CHANGED,
                21L, 1001L, "CREATED", first), 1001L, "CREATED", 10L, 8L)).isTrue();
        assertThat(comments.apply(commentEvent(
                "99999999-1111-4111-8111-111111111111", EventTopics.COMMENT_CHANGED,
                22L, 1001L, "DELETED", older), 1001L, "DELETED", 9L, 99L)).isTrue();
        assertThat(comments.apply(commentEvent(
                "77777777-1111-4111-8111-111111111111", EventTopics.COMMENT_CHANGED,
                23L, 1001L, "HIDDEN", sameMillisecondNewer), 1001L, "HIDDEN", 11L, 7L)).isTrue();
        assertThat(comments.apply(commentEvent(
                "55555555-1111-4111-8111-111111111111", EventTopics.COMMENT_CHANGED,
                24L, 1001L, "CREATED", sameMillisecondOlder), 1001L, "CREATED", 10L, 88L)).isTrue();

        assertThat(interactions.apply(interactionEvent(
                "66666666-2222-4222-8222-222222222222", 1001L, "LIKE", first),
                1001L, "LIKE", 10L, 5L)).isTrue();
        assertThat(interactions.apply(interactionEvent(
                "99999999-2222-4222-8222-222222222222", 1001L, "UNLIKE", older),
                1001L, "UNLIKE", 9L, 50L)).isTrue();
        assertThat(interactions.apply(interactionEvent(
                "77777777-2222-4222-8222-222222222222", 1001L, "FAVORITE", sameMillisecondNewer),
                1001L, "FAVORITE", 11L, 4L)).isTrue();
        assertThat(interactions.apply(interactionEvent(
                "55555555-2222-4222-8222-222222222222", 1001L, "UNFAVORITE", sameMillisecondOlder),
                1001L, "UNFAVORITE", 10L, 44L)).isTrue();

        assertThat(commentCount()).isEqualTo(7L);
        assertThat(likeCount()).isEqualTo(4L);
        assertThat(watermarkEvent("COMMENT")).isEqualTo("77777777-1111-4111-8111-111111111111");
        assertThat(watermarkEvent("LIKE")).isEqualTo("77777777-2222-4222-8222-222222222222");
        assertThat(watermarkVersion("COMMENT")).isEqualTo(11L);
        assertThat(watermarkVersion("LIKE")).isEqualTo(11L);
    }

    @Test
    void invalidAbsoluteSnapshotRollsBackTheConsumerClaimAndWatermark() {
        String eventId = "99999999-9999-4999-8999-999999999999";
        ValidatedEvent event = interactionEvent(eventId, 1001L, "LIKE");

        assertThatThrownBy(() -> interactions.apply(event, 1001L, "LIKE", 1L, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Projected counter cannot be negative");
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM interaction_projection_dedup WHERE event_id = :eventId
                        """).param("eventId", eventId).query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM content_counter_projection_watermark")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void versionedWatermarkRejectsLegacyEventsAndInvalidSourceVersions() {
        String versionedId = "aaaaaaaa-1111-4111-8111-111111111111";
        assertThat(comments.apply(commentEvent(versionedId, EventTopics.COMMENT_CHANGED,
                31L, 1001L, "CREATED", Instant.parse("2026-08-01T12:00:00Z")),
                1001L, "CREATED", 5L, 6L)).isTrue();

        assertThat(comments.apply(commentEvent(
                "bbbbbbbb-1111-4111-8111-111111111111", EventTopics.COMMENT_CHANGED,
                32L, 1001L, "CREATED", Instant.parse("2026-08-01T13:00:00Z")),
                1001L, "CREATED")).isTrue();
        assertThat(commentCount()).isEqualTo(6L);
        assertThat(watermarkEvent("COMMENT")).isEqualTo(versionedId);

        String invalidId = "cccccccc-1111-4111-8111-111111111111";
        assertThatThrownBy(() -> comments.apply(commentEvent(
                invalidId, EventTopics.COMMENT_CHANGED, 33L, 1001L, "CREATED"),
                1001L, "CREATED", 0L, 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Projected counter version must be positive");
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM comment_projection_dedup WHERE event_id = :eventId
                        """).param("eventId", invalidId).query(Integer.class).single()).isZero();
    }

    @Test
    void legacyWatermarksUseTimeThenEventIdAndAcceptMissingHistoricalTieBreaker() {
        jdbcClient.sql("""
                        INSERT INTO content_counter_projection_watermark(
                            counter_type, post_id, occurred_at, event_id)
                        VALUES ('LIKE', 1001, TIMESTAMP '2026-08-01 14:00:00', NULL)
                        """).update();

        assertThat(interactions.apply(interactionEvent(
                "aaaaaaaa-2222-4222-8222-222222222222", 1001L, "LIKE",
                Instant.parse("2026-08-01T14:00:00Z")), 1001L, "LIKE")).isTrue();
        assertThat(interactions.apply(interactionEvent(
                "bbbbbbbb-2222-4222-8222-222222222222", 1001L, "UNLIKE",
                Instant.parse("2026-08-01T14:00:01Z")), 1001L, "UNLIKE")).isTrue();
        assertThat(interactions.apply(interactionEvent(
                "cccccccc-2222-4222-8222-222222222222", 1001L, "LIKE",
                Instant.parse("2026-08-01T13:59:59Z")), 1001L, "LIKE")).isTrue();

        assertThat(likeCount()).isZero();
        assertThat(watermarkEvent("LIKE")).isEqualTo("bbbbbbbb-2222-4222-8222-222222222222");
    }

    private void createPostTable() {
        jdbcClient.sql("""
                CREATE TABLE post(
                  id BIGINT PRIMARY KEY,
                  like_count BIGINT DEFAULT 0,
                  comment_count BIGINT DEFAULT 0)
                """).update();
    }

    private void assertPayloadConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Event ID is already bound to a different payload");
    }

    private String fingerprint(String table, String eventId) {
        String sql = "SELECT event_fingerprint FROM " + table + " WHERE event_id = :eventId";
        return jdbcClient.sql(sql).param("eventId", eventId).query(String.class).single();
    }

    private ValidatedEvent commentEvent(String eventId, String topic, long commentId, long postId, String changeType) {
        return commentEvent(eventId, topic, commentId, postId, changeType,
                Instant.parse("2026-08-01T10:15:30Z"));
    }

    private ValidatedEvent commentEvent(String eventId, String topic, long commentId, long postId,
                                        String changeType, Instant occurredAt) {
        return new ValidatedEvent(UUID.fromString(eventId), topic, 1,
                occurredAt, "dc-comment", Long.toString(commentId),
                objectMapper.createObjectNode()
                        .put("commentId", commentId)
                        .put("postId", postId)
                        .put("changeType", changeType));
    }

    private ValidatedEvent interactionEvent(String eventId, long postId, String interactionType) {
        return interactionEvent(eventId, postId, interactionType,
                Instant.parse("2026-08-01T10:15:30Z"));
    }

    private ValidatedEvent interactionEvent(String eventId, long postId, String interactionType,
                                            Instant occurredAt) {
        return new ValidatedEvent(UUID.fromString(eventId), EventTopics.INTERACTION_CHANGED, 1,
                occurredAt, "dc-interaction", Long.toString(postId),
                objectMapper.createObjectNode()
                        .put("actorId", 7L)
                        .put("postId", postId)
                        .put("interactionType", interactionType));
    }

    private long commentCount() {
        return jdbcClient.sql("SELECT comment_count FROM post WHERE id = 1001").query(Long.class).single();
    }

    private long likeCount() {
        return jdbcClient.sql("SELECT like_count FROM post WHERE id = 1001").query(Long.class).single();
    }

    private String watermarkEvent(String counterType) {
        return jdbcClient.sql("""
                        SELECT event_id FROM content_counter_projection_watermark
                        WHERE counter_type = :counterType AND post_id = 1001
                        """)
                .param("counterType", counterType)
                .query(String.class)
                .single();
    }

    private Long watermarkVersion(String counterType) {
        return jdbcClient.sql("""
                        SELECT source_version FROM content_counter_projection_watermark
                        WHERE counter_type = :counterType AND post_id = 1001
                        """)
                .param("counterType", counterType)
                .query(Long.class)
                .single();
    }

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:content-projection-dedup;MODE=MySQL;DB_CLOSE_DELAY=-1");
            return dataSource;
        }

        @Bean
        JdbcClient jdbcClient(DataSource dataSource) {
            return JdbcClient.create(dataSource);
        }

        @Bean
        ContentCounterProjectionCoordinator counterCoordinator(JdbcClient jdbcClient) {
            return new ContentCounterProjectionCoordinator(jdbcClient);
        }

        @Bean
        ContentCommentProjectionRepository comments(JdbcClient jdbcClient,
                                                    ContentCounterProjectionCoordinator coordinator) {
            return new ContentCommentProjectionRepository(jdbcClient, coordinator);
        }

        @Bean
        ContentInteractionProjectionRepository interactions(JdbcClient jdbcClient,
                                                            ContentCounterProjectionCoordinator coordinator) {
            return new ContentInteractionProjectionRepository(jdbcClient, coordinator);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
