package io.github.devcircle.user.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.user.infrastructure.UserGraphRepository;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringJUnitConfig(UserGraphFollowStateTest.TestConfig.class)
class UserGraphFollowStateTest {
    @Autowired
    @Qualifier("normalService")
    private UserGraphService service;
    @Autowired
    @Qualifier("failingService")
    private UserGraphService failingService;
    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        jdbcClient.sql("DROP ALL OBJECTS").update();
        jdbcClient.sql("""
                CREATE TABLE user_profile (
                    user_id BIGINT PRIMARY KEY,
                    username VARCHAR(64) NOT NULL UNIQUE,
                    avatar_url VARCHAR(512),
                    bio VARCHAR(512),
                    follower_count BIGINT NOT NULL DEFAULT 0,
                    following_count BIGINT NOT NULL DEFAULT 0,
                    post_count BIGINT NOT NULL DEFAULT 0,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """).update();
        jdbcClient.sql("""
                CREATE TABLE user_follow (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    follower_id BIGINT NOT NULL,
                    followee_id BIGINT NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(follower_id, followee_id))
                """).update();
        jdbcClient.sql("""
                CREATE TABLE user_idempotency (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    idempotency_key VARCHAR(128) NOT NULL,
                    operation VARCHAR(32) NOT NULL,
                    target_user_id BIGINT NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(user_id, idempotency_key))
                """).update();
        jdbcClient.sql("""
                CREATE TABLE outbox_event (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    event_id CHAR(36) NOT NULL UNIQUE,
                    topic VARCHAR(128) NOT NULL,
                    aggregate_type VARCHAR(64) NOT NULL,
                    aggregate_id VARCHAR(64) NOT NULL,
                    payload JSON NOT NULL,
                    status VARCHAR(32) NOT NULL DEFAULT 'NEW',
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """).update();
        jdbcClient.sql("CREATE TABLE consumer_dedup(event_id CHAR(36) PRIMARY KEY, topic VARCHAR(128) NOT NULL)")
                .update();
        seedProfile(1L, "Observer");
        seedProfile(2L, "Author");
        seedProfile(3L, "Explorer");
    }

    @Test
    void followAndUnfollowChangeBothCountersAndEmitOnlyRealTransitions() {
        var followed = service.follow(1L, 2L, "follow-key");
        var sameFollowRequest = service.follow(1L, 2L, "follow-key");
        var alreadyFollowing = service.follow(1L, 2L, "follow-second-key");

        assertThat(followed.followed()).isTrue();
        assertThat(followed.duplicated()).isFalse();
        assertThat(sameFollowRequest.followed()).isTrue();
        assertThat(sameFollowRequest.duplicated()).isTrue();
        assertThat(alreadyFollowing.duplicated()).isTrue();
        assertCounts(1L, 0, 1);
        assertCounts(2L, 1, 0);
        assertThat(eventCount()).isEqualTo(1);

        var unfollowed = service.unfollow(1L, 2L, "unfollow-key");
        var sameUnfollowRequest = service.unfollow(1L, 2L, "unfollow-key");
        var alreadyUnfollowed = service.unfollow(1L, 2L, "unfollow-second-key");

        assertThat(unfollowed.followed()).isFalse();
        assertThat(unfollowed.duplicated()).isFalse();
        assertThat(sameUnfollowRequest.duplicated()).isTrue();
        assertThat(alreadyUnfollowed.duplicated()).isTrue();
        assertCounts(1L, 0, 0);
        assertCounts(2L, 0, 0);
        assertThat(eventCount()).isEqualTo(2);
    }

    @Test
    void operationKeysStayBoundToTheirTargetAndOperation() {
        service.follow(1L, 2L, "bound-key");

        assertCode(() -> service.follow(1L, 3L, "bound-key"), ErrorCode.DUPLICATE_OPERATION);
        assertCode(() -> service.unfollow(1L, 2L, "bound-key"), ErrorCode.DUPLICATE_OPERATION);

        assertThat(service.isFollowing(1L, 2L)).isTrue();
        assertThat(service.isFollowing(1L, 3L)).isFalse();
        assertThat(eventCount()).isEqualTo(1);
    }

    @Test
    void missingProfilesAndEventFailuresDoNotBurnTheRequestKey() {
        assertCode(() -> service.follow(1L, 99L, "recoverable-key"), ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(idempotencyCount("recoverable-key")).isZero();
        seedProfile(99L, "Late profile");
        assertThat(service.follow(1L, 99L, "recoverable-key").followed()).isTrue();

        assertThatThrownBy(() -> failingService.follow(1L, 3L, "failing-event-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("serialize follow event");

        assertThat(service.isFollowing(1L, 3L)).isFalse();
        assertCounts(1L, 0, 1);
        assertCounts(3L, 0, 0);
        assertThat(idempotencyCount("failing-event-key")).isZero();

        assertThat(service.follow(1L, 3L, "failing-event-key").followed()).isTrue();
        assertCounts(1L, 0, 2);
        assertCounts(3L, 1, 0);
    }

    @Test
    void graphQueriesReturnFollowersFolloweesAndTwoHopCandidates() {
        service.follow(1L, 2L, "graph-1-2");
        service.follow(3L, 2L, "graph-3-2");

        assertThat(service.followees(1L)).containsExactly(2L);
        assertThat(service.followers(2L)).containsExactlyInAnyOrder(1L, 3L);
        assertThat(service.recommendations(1L)).singleElement().satisfies(item -> {
            assertThat(item.userId()).isEqualTo(3L);
            assertThat(item.mutualFollowees()).isEqualTo(1);
            assertThat(item.reason()).contains("1 个共同关注");
        });
    }

    @Test
    void followValidationRejectsInvalidActorsTargetsAndKeysBeforeDatabaseWrites() {
        assertCode(() -> service.follow(null, 2L, "key"), ErrorCode.UNAUTHORIZED);
        assertCode(() -> service.follow(0L, 2L, "key"), ErrorCode.UNAUTHORIZED);
        assertCode(() -> service.follow(1L, null, "key"), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.follow(1L, 0L, "key"), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.follow(1L, 1L, "key"), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.follow(1L, 2L, null), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.follow(1L, 2L, " "), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.follow(1L, 2L, "x".repeat(129)), ErrorCode.VALIDATION_FAILED);

        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM user_idempotency").query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM user_follow").query(Long.class).single()).isZero();
    }

    @Test
    void batchProfilesFilterInvalidAndDuplicateIdsWithoutIssuingInvalidQueries() {
        assertThat(service.profiles(null)).isEmpty();
        assertThat(service.profiles(List.of())).isEmpty();
        assertThat(service.profiles(Arrays.asList(null, -1L, 0L))).isEmpty();

        assertThat(service.profiles(Arrays.asList(2L, 1L, 2L, null, -1L)))
                .extracting(UserGraphRepository.UserProfile::userId)
                .containsExactly(1L, 2L);
    }

    private void seedProfile(Long userId, String username) {
        jdbcClient.sql("INSERT INTO user_profile(user_id, username) VALUES (:userId, :username)")
                .param("userId", userId).param("username", username).update();
    }

    private void assertCounts(Long userId, long followers, long following) {
        var profile = service.profile(userId).orElseThrow();
        assertThat(profile.followerCount()).isEqualTo(followers);
        assertThat(profile.followingCount()).isEqualTo(following);
    }

    private long eventCount() {
        return jdbcClient.sql("SELECT COUNT(*) FROM outbox_event WHERE topic = 'follow.changed'")
                .query(Long.class).single();
    }

    private long idempotencyCount(String key) {
        return jdbcClient.sql("SELECT COUNT(*) FROM user_idempotency WHERE idempotency_key = :key")
                .param("key", key).query(Long.class).single();
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
            dataSource.setURL("jdbc:h2:mem:user-follow-state;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
            return dataSource;
        }

        @Bean
        JdbcClient jdbcClient(DataSource dataSource) {
            return JdbcClient.create(dataSource);
        }

        @Bean
        PlatformTransactionManager userTransactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean("normalRepository")
        UserGraphRepository normalRepository(JdbcClient jdbcClient) {
            return new UserGraphRepository(jdbcClient, new ObjectMapper().findAndRegisterModules());
        }

        @Bean("failingRepository")
        UserGraphRepository failingRepository(JdbcClient jdbcClient) {
            ObjectMapper failingMapper = new ObjectMapper() {
                @Override
                public String writeValueAsString(Object value) throws JsonProcessingException {
                    throw new JsonProcessingException("serialization offline") { };
                }
            };
            return new UserGraphRepository(jdbcClient, failingMapper);
        }

        @Bean("normalService")
        UserGraphService normalService(@Qualifier("normalRepository") UserGraphRepository repository) {
            return new UserGraphService(repository);
        }

        @Bean("failingService")
        UserGraphService failingService(@Qualifier("failingRepository") UserGraphRepository repository) {
            return new UserGraphService(repository);
        }
    }
}
