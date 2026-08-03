package io.github.devcircle.interaction.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringJUnitConfig(InteractionRepositoryStateTest.TestConfig.class)
class InteractionRepositoryStateTest {
    @Autowired
    @Qualifier("normalRepository")
    private InteractionRepository repository;
    @Autowired
    @Qualifier("failingRepository")
    private InteractionRepository failingRepository;
    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        jdbcClient.sql("DROP ALL OBJECTS").update();
        jdbcClient.sql("""
                CREATE TABLE like_record (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    target_type VARCHAR(32) NOT NULL,
                    target_id BIGINT NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(user_id, target_type, target_id))
                """).update();
        jdbcClient.sql("""
                CREATE TABLE favorite_record (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    post_id BIGINT NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(user_id, post_id))
                """).update();
        jdbcClient.sql("""
                CREATE TABLE post_counter (
                    post_id BIGINT PRIMARY KEY,
                    like_count BIGINT NOT NULL DEFAULT 0,
                    favorite_count BIGINT NOT NULL DEFAULT 0,
                    projection_version BIGINT NOT NULL DEFAULT 0,
                    comment_count BIGINT NOT NULL DEFAULT 0,
                    view_count BIGINT NOT NULL DEFAULT 0)
                """).update();
        jdbcClient.sql("""
                CREATE TABLE idempotency_record (
                    idempotency_key VARCHAR(128) PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    operation VARCHAR(64) NOT NULL,
                    resource_id BIGINT,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
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
    }

    @Test
    void likesAndFavoritesChangeCountersAndEventsExactlyOncePerRealTransition() throws Exception {
        var like = repository.insertLike(7L, 1001L, 10, 42L, "like-key");
        var sameLikeRequest = repository.insertLike(7L, 1001L, 10, 42L, "like-key");
        var alreadyLiked = repository.insertLike(7L, 1001L, 10, 42L, "like-second-key");
        var favorite = repository.insertFavorite(7L, 1001L, 10, 42L, "favorite-key");
        var alreadyFavorite = repository.insertFavorite(7L, 1001L, 10, 42L, "favorite-second-key");

        assertThat(like.inserted()).isTrue();
        assertThat(sameLikeRequest.duplicateRequest()).isTrue();
        assertThat(alreadyLiked.inserted()).isFalse();
        assertThat(favorite.inserted()).isTrue();
        assertThat(alreadyFavorite.inserted()).isFalse();
        assertState(7L, 1001L, true, true, 11, 1);
        assertThat(eventCount()).isEqualTo(2);

        var unlike = repository.deleteLike(7L, 1001L, 10, 42L, "unlike-key");
        var sameUnlikeRequest = repository.deleteLike(7L, 1001L, 10, 42L, "unlike-key");
        var alreadyUnliked = repository.deleteLike(7L, 1001L, 10, 42L, "unlike-second-key");
        var unfavorite = repository.deleteFavorite(7L, 1001L, 10, 42L, "unfavorite-key");
        var alreadyUnfavorited = repository.deleteFavorite(7L, 1001L, 10, 42L, "unfavorite-second-key");

        assertThat(unlike.inserted()).isTrue();
        assertThat(sameUnlikeRequest.duplicateRequest()).isTrue();
        assertThat(alreadyUnliked.inserted()).isFalse();
        assertThat(unfavorite.inserted()).isTrue();
        assertThat(alreadyUnfavorited.inserted()).isFalse();
        assertState(7L, 1001L, false, false, 10, 0);
        assertThat(eventCount()).isEqualTo(4);
        ObjectMapper mapper = new ObjectMapper();
        assertThat(jdbcClient.sql("SELECT payload FROM outbox_event ORDER BY id")
                .query(String.class).list())
                .extracting(payload -> {
                    try {
                        var root = mapper.readTree(payload);
                        if (root.isTextual()) root = mapper.readTree(root.textValue());
                        return root.path("payload").path("likeCount").asLong();
                    } catch (Exception ex) {
                        throw new AssertionError(ex);
                    }
                })
                .containsExactly(11L, 11L, 10L, 10L);
        assertThat(jdbcClient.sql("SELECT payload FROM outbox_event ORDER BY id")
                .query(String.class).list())
                .extracting(payload -> {
                    try {
                        var jsonRoot = mapper.readTree(payload);
                        if (jsonRoot.isTextual()) jsonRoot = mapper.readTree(jsonRoot.textValue());
                        return jsonRoot.path("payload").path("counterVersion").asLong();
                    } catch (Exception ex) {
                        throw new AssertionError(ex);
                    }
                })
                .containsExactly(1L, 2L, 3L, 4L);
        assertThat(jdbcClient.sql("SELECT payload FROM outbox_event ORDER BY id")
                .query(String.class).list())
                .extracting(payload -> {
                    try {
                        var jsonRoot = mapper.readTree(payload);
                        if (jsonRoot.isTextual()) jsonRoot = mapper.readTree(jsonRoot.textValue());
                        return jsonRoot.path("payload").path("receiverId").asLong();
                    } catch (Exception ex) {
                        throw new AssertionError(ex);
                    }
                })
                .containsOnly(42L);
    }

    @Test
    void idempotencyKeysCannotMoveAcrossUsersPostsOrOperations() {
        repository.insertLike(7L, 1001L, 0, 42L, "bound-key");

        assertCode(() -> repository.insertLike(8L, 1001L, 0, 42L, "bound-key"),
                ErrorCode.DUPLICATE_OPERATION);
        assertCode(() -> repository.insertLike(7L, 1002L, 0, 42L, "bound-key"),
                ErrorCode.DUPLICATE_OPERATION);
        assertCode(() -> repository.insertFavorite(7L, 1001L, 0, 42L, "bound-key"),
                ErrorCode.DUPLICATE_OPERATION);
        assertCode(() -> repository.deleteLike(7L, 1001L, 0, 42L, "bound-key"),
                ErrorCode.DUPLICATE_OPERATION);

        assertThat(count("like_record")).isEqualTo(1);
        assertThat(count("favorite_record")).isZero();
        assertThat(eventCount()).isEqualTo(1);
    }

    @Test
    void countersClampNegativeBaselinesAndReadModelsHandleMissingCounters() {
        var missing = repository.interactionState(9L, 9999L);
        assertThat(missing.liked()).isFalse();
        assertThat(missing.favorited()).isFalse();
        assertThat(missing.likeCount()).isNull();
        assertThat(missing.favoriteCount()).isNull();

        repository.insertFavorite(9L, 2001L, -50, 42L, "clamped-favorite");
        repository.insertFavorite(9L, 2002L, 3, 42L, "second-favorite");

        assertState(9L, 2001L, false, true, 0, 1);
        assertThat(repository.favoritePostIds(9L, 0)).hasSize(1);
        assertThat(repository.favoritePostIds(9L, 1000)).containsExactlyInAnyOrder(2001L, 2002L);

        repository.deleteFavorite(9L, 2001L, 0, 42L, "remove-clamped");
        repository.deleteFavorite(9L, 2001L, 0, 42L, "remove-clamped-again");
        assertState(9L, 2001L, false, false, 0, 0);
    }

    @Test
    void eventSerializationFailureRollsBackStateCounterAndIdempotencyTogether() {
        assertThatThrownBy(() -> failingRepository.insertLike(7L, 3001L, 12, 42L, "failing-like"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("serialize interaction event");

        assertThat(count("like_record")).isZero();
        assertThat(count("post_counter")).isZero();
        assertThat(count("idempotency_record")).isZero();
        assertThat(eventCount()).isZero();

        var retry = repository.insertLike(7L, 3001L, 12, 42L, "failing-like");
        assertThat(retry.inserted()).isTrue();
        assertState(7L, 3001L, true, false, 13, 0);
    }

    @Test
    void invalidNotificationReceiverFailsBeforeClaimingTheRequestKey() {
        assertThatThrownBy(() -> repository.insertLike(7L, 4001L, 0, 0, "invalid-receiver"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("receiverId");

        assertThat(count("idempotency_record")).isZero();
        assertThat(count("like_record")).isZero();
        assertThat(eventCount()).isZero();
    }

    private void assertState(Long userId, Long postId, boolean liked, boolean favorited,
                             long likeCount, long favoriteCount) {
        var state = repository.interactionState(userId, postId);
        assertThat(state.liked()).isEqualTo(liked);
        assertThat(state.favorited()).isEqualTo(favorited);
        assertThat(state.likeCount()).isEqualTo(likeCount);
        assertThat(state.favoriteCount()).isEqualTo(favoriteCount);
    }

    private long eventCount() {
        return jdbcClient.sql("SELECT COUNT(*) FROM outbox_event WHERE topic = 'interaction.changed'")
                .query(Long.class).single();
    }

    private long count(String table) {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
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
            dataSource.setURL("jdbc:h2:mem:interaction-state;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
            return dataSource;
        }

        @Bean
        JdbcClient jdbcClient(DataSource dataSource) {
            return JdbcClient.create(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean("normalRepository")
        InteractionRepository normalRepository(JdbcClient jdbcClient) {
            return new InteractionRepository(jdbcClient, new ObjectMapper().findAndRegisterModules());
        }

        @Bean("failingRepository")
        InteractionRepository failingRepository(JdbcClient jdbcClient) {
            ObjectMapper failingMapper = new ObjectMapper() {
                @Override
                public String writeValueAsString(Object value) throws JsonProcessingException {
                    throw new JsonProcessingException("serialization offline") { };
                }
            };
            return new InteractionRepository(jdbcClient, failingMapper);
        }
    }
}
