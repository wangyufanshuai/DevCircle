package io.github.devcircle.user.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.common.event.EventTopics;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserGraphRepositoryTest {
    private JdbcClient jdbcClient;
    private UserGraphRepository repository;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:user-graph;MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbcClient = JdbcClient.create(dataSource);
        jdbcClient.sql("DROP ALL OBJECTS").update();
        jdbcClient.sql("""
                CREATE TABLE user_profile (
                  user_id BIGINT PRIMARY KEY, username VARCHAR(64) NOT NULL,
                  avatar_url VARCHAR(512), bio VARCHAR(512), follower_count BIGINT DEFAULT 0 NOT NULL,
                  following_count BIGINT DEFAULT 0 NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
                """).update();
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
        jdbcClient.sql("CREATE TABLE consumer_dedup(event_id CHAR(36) PRIMARY KEY, topic VARCHAR(128) NOT NULL)").update();
        jdbcClient.sql("""
                CREATE TABLE outbox_event (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, event_id CHAR(36) UNIQUE NOT NULL, topic VARCHAR(128) NOT NULL,
                  aggregate_type VARCHAR(64) NOT NULL, aggregate_id VARCHAR(64) NOT NULL, payload JSON NOT NULL,
                  status VARCHAR(32) DEFAULT 'NEW' NOT NULL)
                """).update();
        repository = new UserGraphRepository(jdbcClient, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void registrationProjectionIsIdempotentAndPreservesTheAuthoritativeDisplayName() {
        assertThat(repository.synchronizeProfile("evt-1", EventTopics.USER_REGISTERED, 7L, "林墨")).isTrue();
        assertThat(repository.synchronizeProfile("evt-1", EventTopics.USER_REGISTERED, 7L, "被忽略的重放")).isFalse();

        assertThat(repository.requireProfile(7L).username()).isEqualTo("林墨");
        assertThatThrownBy(() -> repository.requireProfile(8L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void followEventContainsBothSidesOfTheRelationship() {
        repository.synchronizeProfile("evt-a", EventTopics.USER_REGISTERED, 1L, "观测者");
        repository.synchronizeProfile("evt-b", EventTopics.USER_REGISTERED, 2L, "林墨");
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
}
