package io.github.devcircle.notification.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.common.event.ValidatedEvent;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringJUnitConfig(NotificationRepositoryStateTest.TestConfig.class)
class NotificationRepositoryStateTest {
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-02T00:00:00Z");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    @Autowired
    private NotificationRepository repository;
    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        jdbcClient.sql("DROP ALL OBJECTS").update();
        jdbcClient.sql("""
                CREATE TABLE consumer_dedup (
                    event_id CHAR(36) PRIMARY KEY,
                    topic VARCHAR(128) NOT NULL,
                    event_fingerprint CHAR(64) NOT NULL,
                    consumed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """).update();
        jdbcClient.sql("""
                CREATE TABLE notification (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    receiver_id BIGINT NOT NULL,
                    actor_id BIGINT NULL,
                    type VARCHAR(64) NOT NULL,
                    title VARCHAR(128) NOT NULL,
                    content VARCHAR(512) NOT NULL,
                    target_type VARCHAR(32) NULL,
                    target_id BIGINT NULL,
                    target_route VARCHAR(255) NULL,
                    read_flag BOOLEAN NOT NULL DEFAULT FALSE,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """).update();
        jdbcClient.sql("""
                CREATE TABLE notification_idempotency_record (
                    idempotency_key VARCHAR(128) PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    operation VARCHAR(48) NOT NULL,
                    resource_id BIGINT NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """).update();
    }

    @Test
    void failedNotificationInsertRollsBackConsumerClaimAndCanBeRetried() {
        String eventId = "5c6f99f9-169d-44dc-a9ab-555cbe5f5a38";
        ValidatedEvent sourceEvent = event(eventId, "follow.changed", Map.of("receiverId", 7L));

        assertThatThrownBy(() -> repository.createOnce(sourceEvent, 7L, 2L,
                "FOLLOW", "新的关注者", "x".repeat(513), "USER", 2L, "/me"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(count("consumer_dedup")).isZero();
        assertThat(count("notification")).isZero();

        assertThat(repository.createOnce(sourceEvent, 7L, 2L,
                "FOLLOW", "新的关注者", "观测者关注了你", "USER", 2L, "/me")).isTrue();
        assertThat(count("consumer_dedup")).isEqualTo(1);
        assertThat(count("notification")).isEqualTo(1);
    }

    @Test
    void markReadIsOwnerScopedAndFailedLookupDoesNotBurnTheKey() {
        long ownerNotification = notification(7L, "AUDIT", false);
        long otherNotification = notification(8L, "FOLLOW", false);

        assertCode(() -> repository.markRead(8L, ownerNotification, "recoverable-key"),
                ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(idempotencyCount("recoverable-key")).isZero();

        assertThat(repository.markRead(7L, ownerNotification, "recoverable-key")).isTrue();
        assertThat(repository.markRead(7L, ownerNotification, "recoverable-key")).isFalse();
        assertCode(() -> repository.markRead(7L, otherNotification, "recoverable-key"),
                ErrorCode.DUPLICATE_OPERATION);
        assertCode(() -> repository.markRead(8L, otherNotification, "recoverable-key"),
                ErrorCode.DUPLICATE_OPERATION);

        assertThat(readFlag(ownerNotification)).isTrue();
        assertThat(readFlag(otherNotification)).isFalse();
    }

    @Test
    void markAllReadIsIdempotentAndKeyCannotCrossUsersOrOperations() {
        long first = notification(7L, "AUDIT", false);
        long second = notification(7L, "INTERACTION", false);
        long other = notification(8L, "FOLLOW", false);

        assertThat(repository.markAllRead(7L, "all-key")).isEqualTo(2);
        long arrivedAfterFirstRequest = notification(7L, "SYSTEM", false);
        assertThat(repository.markAllRead(7L, "all-key")).isEqualTo(2);
        assertCode(() -> repository.markAllRead(8L, "all-key"), ErrorCode.DUPLICATE_OPERATION);
        assertCode(() -> repository.markRead(7L, first, "all-key"), ErrorCode.DUPLICATE_OPERATION);

        assertThat(readFlag(first)).isTrue();
        assertThat(readFlag(second)).isTrue();
        assertThat(readFlag(arrivedAfterFirstRequest)).isFalse();
        assertThat(readFlag(other)).isFalse();
        assertThat(repository.markAllRead(7L, "new-all-key")).isEqualTo(1);
    }

    @Test
    void listAppliesOwnerTypeCursorAndUnreadFilters() {
        long oldest = notification(7L, "AUDIT", false);
        long middle = notification(7L, "FOLLOW", false);
        long newest = notification(7L, "AUDIT", true);
        notification(8L, "AUDIT", false);

        assertThat(repository.list(7L, null, null, 10))
                .extracting(view -> view.id())
                .containsExactly(newest, middle, oldest);
        assertThat(repository.list(7L, "audit", newest, 10))
                .extracting(view -> view.id())
                .containsExactly(oldest);
        assertThat(repository.unreadCount(7L)).isEqualTo(2);
        assertThat(repository.unreadCount(8L)).isEqualTo(1);
    }

    private long notification(long receiverId, String type, boolean read) {
        jdbcClient.sql("""
                        INSERT INTO notification(receiver_id, type, title, content, read_flag)
                        VALUES (:receiverId, :type, 'title', 'content', :read)
                        """)
                .param("receiverId", receiverId).param("type", type).param("read", read).update();
        return jdbcClient.sql("SELECT MAX(id) FROM notification").query(Long.class).single();
    }

    private long count(String table) {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    private long idempotencyCount(String key) {
        return jdbcClient.sql("SELECT COUNT(*) FROM notification_idempotency_record WHERE idempotency_key = :key")
                .param("key", key).query(Long.class).single();
    }

    private boolean readFlag(long notificationId) {
        return jdbcClient.sql("SELECT read_flag FROM notification WHERE id = :id")
                .param("id", notificationId).query(Boolean.class).single();
    }

    private ValidatedEvent event(String eventId, String topic, Map<String, Object> payload) {
        return new ValidatedEvent(UUID.fromString(eventId), topic, 1, OCCURRED_AT, "test-producer", "7",
                OBJECT_MAPPER.valueToTree(payload));
    }

    private void assertCode(ThrowingAction action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(expected));
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:notification-state;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
            return dataSource;
        }

        @Bean
        JdbcClient jdbcClient(DataSource dataSource) {
            return JdbcClient.create(dataSource);
        }

        @Bean("notificationTransactionManager")
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        NotificationRepository notificationRepository(JdbcClient jdbcClient) {
            return new NotificationRepository(jdbcClient);
        }
    }
}
