package io.github.devcircle.user.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.common.event.EventFingerprint;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.common.event.ValidatedEvent;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
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

@SpringJUnitConfig(UserGraphRepositoryTest.TestConfig.class)
class UserGraphRepositoryTest {
    private static final String EVENT_ID_1 = "3a68db2b-729a-4d50-8c89-22d6f404b6c3";
    private static final String EVENT_ID_2 = "a51d9baf-683a-4ad7-b67d-035cdccb79a5";
    private static final String EVENT_ID_3 = "cf9c50e8-e865-4998-98ca-9a6c990b535b";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private UserGraphRepository repository;

    @BeforeEach
    void setUp() {
        jdbcClient.sql("DROP ALL OBJECTS").update();
        createUserProfileTable();
        jdbcClient.sql("""
                CREATE TABLE user_follow (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, follower_id BIGINT NOT NULL, followee_id BIGINT NOT NULL,
                  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, UNIQUE(follower_id, followee_id))
                """).update();
        jdbcClient.sql("""
                CREATE TABLE user_idempotency (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, idempotency_key VARCHAR(128) NOT NULL,
                  operation VARCHAR(32) NOT NULL, target_user_id BIGINT NOT NULL, UNIQUE(user_id, idempotency_key))
                """).update();
        jdbcClient.sql("""
                CREATE TABLE consumer_dedup(
                  event_id CHAR(36) PRIMARY KEY, topic VARCHAR(128) NOT NULL,
                  event_fingerprint CHAR(64) NOT NULL DEFAULT '0000000000000000000000000000000000000000000000000000000000000000')
                """).update();
        jdbcClient.sql("""
                CREATE TABLE outbox_event (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, event_id CHAR(36) UNIQUE NOT NULL, topic VARCHAR(128) NOT NULL,
                  aggregate_type VARCHAR(64) NOT NULL, aggregate_id VARCHAR(64) NOT NULL, payload JSON NOT NULL,
                  status VARCHAR(32) DEFAULT 'NEW' NOT NULL)
                """).update();
    }

    private void createUserProfileTable() {
        jdbcClient.sql("""
                CREATE TABLE user_profile (
                  user_id BIGINT PRIMARY KEY, username VARCHAR(64) NOT NULL,
                  avatar_url VARCHAR(512), bio VARCHAR(512), follower_count BIGINT DEFAULT 0 NOT NULL,
                  following_count BIGINT DEFAULT 0 NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
                """).update();
    }

    @Test
    void registrationProjectionIsIdempotentAndPreservesTheAuthoritativeDisplayName() {
        ValidatedEvent event = registrationEvent(EVENT_ID_1, 7L, "林墨");
        assertThat(repository.synchronizeProfile(event, 7L, "林墨")).isTrue();
        assertThat(repository.synchronizeProfile(event, 7L, "林墨")).isFalse();
        assertPayloadConflict(registrationEvent(EVENT_ID_1, 7L, "被拒绝的变形重放"), 7L, "被拒绝的变形重放");

        assertThat(repository.requireProfile(7L).username()).isEqualTo("林墨");
        assertThatThrownBy(() -> repository.requireProfile(8L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void eventIdCannotBeSilentlyReusedAcrossTopics() {
        ValidatedEvent original = registrationEvent(EVENT_ID_1, 7L, "林墨");
        assertThat(repository.synchronizeProfile(original, 7L, "林墨")).isTrue();
        ValidatedEvent changedTopic = new ValidatedEvent(original.eventId(), EventTopics.FOLLOW_CHANGED, 1,
                original.occurredAt(), "dc-user", original.aggregateId(), original.payload());

        assertThatThrownBy(() -> repository.synchronizeProfile(
                changedTopic, 7L, "林墨"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Event ID is already bound to topic user.registered");
    }

    @Test
    void eventIdCannotBeReusedWithChangedEnvelopeIdentity() {
        ValidatedEvent original = registrationEvent(EVENT_ID_2, 8L, "星图员");
        assertThat(repository.synchronizeProfile(original, 8L, "星图员")).isTrue();

        assertPayloadConflict(new ValidatedEvent(original.eventId(), original.type(), 1,
                original.occurredAt().plusSeconds(1), original.producer(), original.aggregateId(), original.payload()),
                8L, "星图员");
        assertPayloadConflict(new ValidatedEvent(original.eventId(), original.type(), 1,
                original.occurredAt(), "another-producer", original.aggregateId(), original.payload()),
                8L, "星图员");
        assertPayloadConflict(new ValidatedEvent(original.eventId(), original.type(), 1,
                original.occurredAt(), original.producer(), "another-aggregate", original.payload()),
                8L, "星图员");
    }

    @Test
    void legacyConsumerRecordIsBoundByItsFirstReplayWithoutChangingTheProfile() {
        ValidatedEvent original = registrationEvent(EVENT_ID_2, 8L, "星图员");
        assertThat(repository.synchronizeProfile(original, 8L, "星图员")).isTrue();
        jdbcClient.sql("UPDATE consumer_dedup SET event_fingerprint = :legacy WHERE event_id = :eventId")
                .param("legacy", "0".repeat(64))
                .param("eventId", EVENT_ID_2)
                .update();

        ValidatedEvent compatibleReplay = registrationEvent(EVENT_ID_2, 8L, "旧记录重放");
        assertThat(repository.synchronizeProfile(compatibleReplay, 8L, "旧记录重放")).isFalse();
        assertThat(repository.requireProfile(8L).username()).isEqualTo("星图员");
        String fingerprint = jdbcClient.sql(
                        "SELECT event_fingerprint FROM consumer_dedup WHERE event_id = :eventId")
                .param("eventId", EVENT_ID_2)
                .query(String.class)
                .single();
        assertThat(fingerprint).isEqualTo(EventFingerprint.sha256(compatibleReplay));
        assertPayloadConflict(original, 8L, "星图员");
    }

    @Test
    void failedProfileWriteRollsBackTheConsumerClaimAndAllowsTheOriginalEventToRetry() {
        ValidatedEvent event = registrationEvent(EVENT_ID_2, 8L, "星图员");
        jdbcClient.sql("DROP TABLE user_profile").update();

        assertThatThrownBy(() -> repository.synchronizeProfile(event, 8L, "星图员"))
                .isInstanceOf(DataAccessException.class);
        Integer claims = jdbcClient.sql("SELECT COUNT(*) FROM consumer_dedup WHERE event_id = :eventId")
                .param("eventId", EVENT_ID_2)
                .query(Integer.class)
                .single();
        assertThat(claims).isZero();

        createUserProfileTable();
        assertThat(repository.synchronizeProfile(event, 8L, "星图员")).isTrue();
        assertThat(repository.requireProfile(8L).username()).isEqualTo("星图员");
    }

    @Test
    void followEventContainsBothSidesOfTheRelationship() {
        repository.synchronizeProfile(registrationEvent(EVENT_ID_1, 1L, "观测者"), 1L, "观测者");
        repository.synchronizeProfile(registrationEvent(EVENT_ID_3, 2L, "林墨"), 2L, "林墨");
        var actor = repository.requireProfile(1L);
        var receiver = repository.requireProfile(2L);

        assertThat(repository.follow(1L, 2L)).isTrue();
        repository.writeFollowChanged(1L, actor, 2L, receiver, true);

        String event = jdbcClient.sql("SELECT CAST(payload AS VARCHAR) FROM outbox_event WHERE topic = 'follow.changed'")
                .query(String.class).single();
        assertThat(event).contains("follow.changed", "观测者", "林墨", "FOLLOWED");
        assertThat(repository.requireProfile(1L).followingCount()).isEqualTo(1);
        assertThat(repository.requireProfile(2L).followerCount()).isEqualTo(1);
    }

    private void assertPayloadConflict(ValidatedEvent event, long userId, String displayName) {
        assertThatThrownBy(() -> repository.synchronizeProfile(event, userId, displayName))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Event ID is already bound to a different payload");
    }

    private ValidatedEvent registrationEvent(String eventId, long userId, String displayName) {
        return new ValidatedEvent(UUID.fromString(eventId), EventTopics.USER_REGISTERED, 1,
                Instant.parse("2026-08-01T10:15:30Z"), "dc-auth", Long.toString(userId),
                objectMapper.createObjectNode()
                        .put("userId", userId)
                        .put("displayName", displayName)
                        .put("status", "ACTIVE"));
    }

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:user-graph;MODE=MySQL;DB_CLOSE_DELAY=-1");
            return dataSource;
        }

        @Bean
        JdbcClient jdbcClient(DataSource dataSource) {
            return JdbcClient.create(dataSource);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        UserGraphRepository userGraphRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
            return new UserGraphRepository(jdbcClient, objectMapper);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
