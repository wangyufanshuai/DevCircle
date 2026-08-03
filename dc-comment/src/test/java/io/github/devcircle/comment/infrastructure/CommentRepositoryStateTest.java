package io.github.devcircle.comment.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.comment.api.CommentThreadView;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringJUnitConfig(CommentRepositoryStateTest.TestConfig.class)
class CommentRepositoryStateTest {
    @Autowired
    private CommentRepository repository;
    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void setUp() {
        jdbcClient.sql("DROP ALL OBJECTS").update();
        jdbcClient.sql("""
                CREATE TABLE comment (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    post_id BIGINT NOT NULL,
                    author_id BIGINT NOT NULL,
                    parent_id BIGINT,
                    root_id BIGINT,
                    content VARCHAR(2000) NOT NULL,
                    status VARCHAR(32) NOT NULL DEFAULT 'VISIBLE',
                    like_count BIGINT NOT NULL DEFAULT 0,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """).update();
        jdbcClient.sql("""
                CREATE TABLE comment_audit_record (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    comment_id BIGINT NOT NULL,
                    auditor_id BIGINT,
                    action VARCHAR(32) NOT NULL,
                    reason VARCHAR(500),
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """).update();
        createOutboxTable();
        jdbcClient.sql("""
                CREATE TABLE post_comment_counter (
                    post_id BIGINT PRIMARY KEY,
                    visible_count BIGINT NOT NULL DEFAULT 0,
                    projection_version BIGINT NOT NULL DEFAULT 0,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """).update();
        jdbcClient.sql("""
                CREATE TABLE idempotency_record (
                    idempotency_key VARCHAR(128) PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    operation VARCHAR(64) NOT NULL,
                    request_fingerprint CHAR(64) NOT NULL,
                    resource_id BIGINT,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """).update();
    }

    @Test
    void twoLevelRepliesRejectInvalidParentsAndRollBackTheirClaims() {
        var root = repository.create(7L, 1001L, null, "root clue", "root-key");
        var reply = repository.create(8L, 1001L, root.id(), "reply clue", "reply-key");

        assertThat(reply.parentId()).isEqualTo(root.id());
        assertThat(reply.rootId()).isEqualTo(root.id());

        assertCode(() -> repository.create(9L, 1001L, reply.id(), "third level", "third-key"),
                ErrorCode.VALIDATION_FAILED);
        var recoveredThirdKey = repository.create(9L, 1001L, root.id(), "valid second level", "third-key");
        assertThat(recoveredThirdKey.rootId()).isEqualTo(root.id());

        assertCode(() -> repository.create(10L, 2002L, root.id(), "cross post", "cross-key"),
                ErrorCode.VALIDATION_FAILED);
        var recoveredCrossKey = repository.create(10L, 1001L, root.id(), "same post", "cross-key");
        assertThat(recoveredCrossKey.postId()).isEqualTo(1001L);

        repository.hide(root.id(), 99L, "hide-root", "contains private information");
        assertCode(() -> repository.create(11L, 1001L, root.id(), "hidden reply", "hidden-parent"),
                ErrorCode.VALIDATION_FAILED);
        assertThat(count("idempotency_record", "idempotency_key = 'hidden-parent'")).isZero();
    }

    @Test
    void createAndDeleteKeysStayBoundAndDeletionEmitsOnce() throws Exception {
        var root = repository.create(7L, 1001L, null, "original", "create-key");
        var replay = repository.create(7L, 1001L, null, "original", "create-key");

        assertThat(replay.id()).isEqualTo(root.id());
        assertThat(replay.content()).isEqualTo("original");
        assertCode(() -> repository.create(7L, 1001L, null, "changed body", "create-key"),
                ErrorCode.DUPLICATE_OPERATION);
        assertCode(() -> repository.create(7L, 2002L, null, "original", "create-key"),
                ErrorCode.DUPLICATE_OPERATION);
        assertCode(() -> repository.create(7L, 1001L, root.id(), "original", "create-key"),
                ErrorCode.DUPLICATE_OPERATION);
        assertCode(() -> repository.create(8L, 1001L, null, "other user", "create-key"),
                ErrorCode.DUPLICATE_OPERATION);
        assertCode(() -> repository.deleteOwn(root.id(), 7L, "create-key"), ErrorCode.DUPLICATE_OPERATION);

        assertCode(() -> repository.deleteOwn(root.id(), 8L, "owner-retry-key"), ErrorCode.FORBIDDEN);
        repository.deleteOwn(root.id(), 7L, "owner-retry-key");
        repository.deleteOwn(root.id(), 7L, "owner-retry-key");

        assertThat(status(root.id())).isEqualTo("DELETED");
        assertThat(count("outbox_event", "topic = 'comment.changed' AND aggregate_id = '" + root.id() + "'"))
                .isEqualTo(2);
        assertThat(count("idempotency_record", "idempotency_key = 'owner-retry-key'"))
                .isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT visible_count FROM post_comment_counter WHERE post_id = 1001")
                .query(Long.class).single()).isZero();
        List<String> payloads = jdbcClient.sql("""
                        SELECT payload FROM outbox_event
                        WHERE aggregate_id = :commentId ORDER BY id
                        """)
                .param("commentId", root.id().toString())
                .query(String.class)
                .list();
        ObjectMapper mapper = new ObjectMapper();
        assertThat(payloads).extracting(payload -> {
            try {
                var jsonRoot = mapper.readTree(payload);
                if (jsonRoot.isTextual()) jsonRoot = mapper.readTree(jsonRoot.textValue());
                return jsonRoot.path("payload").path("commentCount").asLong();
            } catch (Exception ex) {
                throw new AssertionError(ex);
            }
        }).containsExactly(1L, 0L);
        assertThat(payloads).extracting(payload -> {
            try {
                var jsonRoot = mapper.readTree(payload);
                if (jsonRoot.isTextual()) jsonRoot = mapper.readTree(jsonRoot.textValue());
                return jsonRoot.path("payload").path("counterVersion").asLong();
            } catch (Exception ex) {
                throw new AssertionError(ex);
            }
        }).containsExactly(1L, 2L);
    }

    @Test
    void hiddenTextIsMaskedForReadersButPreservedForModeratorsAndAuditedOnce() {
        var root = repository.create(7L, 1001L, null, "root", "root-key");
        var reply = repository.create(8L, 1001L, root.id(), "private phone number", "reply-key");

        repository.hide(reply.id(), 99L, "hide-key", "contains private information");
        repository.hide(reply.id(), 99L, "hide-key", "contains private information");
        assertCode(() -> repository.hide(reply.id(), 99L, "hide-key", "different moderation reason"),
                ErrorCode.DUPLICATE_OPERATION);
        repository.hide(reply.id(), 99L, "hide-again-key", "second moderation attempt");

        CommentThreadView readerThread = repository.listThreads(1001L, null, 20, false).getFirst();
        CommentThreadView moderatorThread = repository.listThreads(1001L, null, 20, true).getFirst();

        assertThat(readerThread.replies()).singleElement().satisfies(view -> {
            assertThat(view.status()).isEqualTo("HIDDEN");
            assertThat(view.content()).isEqualTo("评论已隐藏");
        });
        assertThat(moderatorThread.replies()).singleElement().satisfies(view ->
                assertThat(view.content()).isEqualTo("private phone number"));
        assertThat(count("comment_audit_record", "comment_id = " + reply.id())).isEqualTo(1);
        assertThat(count("outbox_event", "aggregate_id = '" + reply.id() + "'")).isEqualTo(2);
    }

    @Test
    void rootCursorPaginationKeepsRepliesAttachedAndDeletedPlaceholdersVisible() {
        var oldest = repository.create(7L, 1001L, null, "oldest", "oldest-key");
        var middle = repository.create(7L, 1001L, null, "middle", "middle-key");
        var newest = repository.create(7L, 1001L, null, "newest", "newest-key");
        var reply = repository.create(8L, 1001L, middle.id(), "middle reply", "middle-reply-key");
        repository.deleteOwn(middle.id(), 7L, "delete-middle");

        List<CommentThreadView> first = repository.listThreads(1001L, null, 2, false);
        List<CommentThreadView> second = repository.listThreads(1001L, middle.id(), 2, false);

        assertThat(first).extracting(thread -> thread.root().id()).containsExactly(newest.id(), middle.id());
        assertThat(first.get(1).root().content()).isEqualTo("[已删除]");
        assertThat(first.get(1).replies()).extracting(view -> view.id()).containsExactly(reply.id());
        assertThat(second).extracting(thread -> thread.root().id()).containsExactly(oldest.id());
    }

    @Test
    void legacyListCursorReturnsOnlyVisibleCommentsAndEmptyThreadsStayEmpty() {
        var oldest = repository.create(7L, 2001L, null, "oldest", "legacy-oldest");
        var middle = repository.create(7L, 2001L, null, "middle", "legacy-middle");
        var newest = repository.create(7L, 2001L, null, "newest", "legacy-newest");

        assertThat(repository.list(2001L, null, 20))
                .extracting(view -> view.id()).containsExactly(newest.id(), middle.id(), oldest.id());
        assertThat(repository.list(2001L, middle.id(), 20))
                .extracting(view -> view.id()).containsExactly(oldest.id());

        repository.hide(middle.id(), 99L, "legacy-hide", "moderation reason");
        repository.deleteOwn(oldest.id(), 7L, "legacy-delete");
        assertThat(repository.list(2001L, null, 20))
                .extracting(view -> view.id()).containsExactly(newest.id());
        assertThat(repository.listThreads(9999L, null, 20, false)).isEmpty();
    }

    @Test
    void missingAndTerminalCommentsFailWithoutBurningNewIdempotencyKeys() {
        assertCode(() -> repository.create(7L, 1001L, 999L, "missing parent", "missing-parent-key"),
                ErrorCode.RESOURCE_NOT_FOUND);
        assertCode(() -> repository.deleteOwn(999L, 7L, "missing-delete-key"), ErrorCode.RESOURCE_NOT_FOUND);
        assertCode(() -> repository.hide(999L, 99L, "missing-hide-key", "moderation reason"),
                ErrorCode.RESOURCE_NOT_FOUND);

        var deleted = repository.create(7L, 1001L, null, "delete me", "terminal-root");
        repository.deleteOwn(deleted.id(), 7L, "terminal-delete");
        assertCode(() -> repository.deleteOwn(deleted.id(), 7L, "terminal-delete-again"), ErrorCode.FORBIDDEN);
        assertCode(() -> repository.hide(deleted.id(), 99L, "terminal-hide", "moderation reason"),
                ErrorCode.RESOURCE_NOT_FOUND);

        assertThat(count("idempotency_record", "idempotency_key IN ("
                + "'missing-parent-key','missing-delete-key','missing-hide-key','terminal-delete-again','terminal-hide')"))
                .isZero();
    }

    @Test
    void inFlightAndMissingCreateReplaysFailExplicitly() {
        jdbcClient.sql("""
                        INSERT INTO idempotency_record(
                            idempotency_key, user_id, operation, request_fingerprint, resource_id)
                        VALUES ('processing-key', 7, 'CREATE_COMMENT', :legacy, NULL)
                        """).param("legacy", "0".repeat(64)).update();
        jdbcClient.sql("""
                        INSERT INTO idempotency_record(
                            idempotency_key, user_id, operation, request_fingerprint, resource_id)
                        VALUES ('missing-resource-key', 7, 'CREATE_COMMENT', :legacy, 999)
                        """).param("legacy", "0".repeat(64)).update();

        assertCode(() -> repository.create(7L, 1001L, null, "processing", "processing-key"),
                ErrorCode.DUPLICATE_OPERATION);
        assertCode(() -> repository.create(7L, 1001L, null, "missing", "missing-resource-key"),
                ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void legacyCreateFingerprintRemainsReplayCompatibleForOneVersion() {
        var created = repository.create(7L, 1001L, null, "original", "legacy-create-key");
        jdbcClient.sql("""
                        UPDATE idempotency_record SET request_fingerprint = :legacy
                        WHERE idempotency_key = 'legacy-create-key'
                        """).param("legacy", "0".repeat(64)).update();

        var replay = repository.create(7L, 2002L, null, "legacy changed body", "legacy-create-key");

        assertThat(replay.id()).isEqualTo(created.id());
        assertThat(replay.content()).isEqualTo("original");
        assertThat(count("comment", "id = " + created.id())).isEqualTo(1);
    }

    @Test
    void mutationKeysCannotMoveAcrossModeratorsOperationsOrComments() {
        var first = repository.create(7L, 1001L, null, "first", "binding-first");
        var second = repository.create(8L, 1001L, null, "second", "binding-second");
        repository.hide(first.id(), 99L, "binding-hide", "moderation reason");

        assertCode(() -> repository.hide(first.id(), 100L, "binding-hide", "other moderator"),
                ErrorCode.DUPLICATE_OPERATION);
        assertCode(() -> repository.hide(second.id(), 99L, "binding-hide", "other comment"),
                ErrorCode.DUPLICATE_OPERATION);
        assertCode(() -> repository.deleteOwn(first.id(), 99L, "binding-hide"),
                ErrorCode.DUPLICATE_OPERATION);
    }

    @Test
    void outboxFailureRollsBackCommentModerationAndRequestKeys() {
        jdbcClient.sql("DROP TABLE outbox_event").update();
        assertThatThrownBy(() -> repository.create(7L, 1001L, null, "recoverable", "outbox-create"))
                .isInstanceOf(DataAccessException.class);
        assertThat(count("comment", "content = 'recoverable'")).isZero();
        assertThat(count("idempotency_record", "idempotency_key = 'outbox-create'")).isZero();
        assertThat(count("post_comment_counter", "post_id = 1001")).isZero();

        createOutboxTable();
        var created = repository.create(7L, 1001L, null, "recoverable", "outbox-create");

        jdbcClient.sql("DROP TABLE outbox_event").update();
        assertThatThrownBy(() -> repository.hide(
                created.id(), 99L, "outbox-hide", "contains private information"))
                .isInstanceOf(DataAccessException.class);
        assertThat(status(created.id())).isEqualTo("VISIBLE");
        assertThat(count("comment_audit_record", "comment_id = " + created.id())).isZero();
        assertThat(count("idempotency_record", "idempotency_key = 'outbox-hide'")).isZero();
        assertThat(jdbcClient.sql("SELECT visible_count FROM post_comment_counter WHERE post_id = 1001")
                .query(Long.class).single()).isEqualTo(1L);

        createOutboxTable();
        repository.hide(created.id(), 99L, "outbox-hide", "contains private information");
        assertThat(status(created.id())).isEqualTo("HIDDEN");
        assertThat(count("comment_audit_record", "comment_id = " + created.id())).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT visible_count FROM post_comment_counter WHERE post_id = 1001")
                .query(Long.class).single()).isZero();
    }

    private void createOutboxTable() {
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

    private String status(Long commentId) {
        return jdbcClient.sql("SELECT status FROM comment WHERE id = :id")
                .param("id", commentId).query(String.class).single();
    }

    private long count(String table, String where) {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + table + " WHERE " + where)
                .query(Long.class).single();
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
            dataSource.setURL("jdbc:h2:mem:comment-state;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
            return dataSource;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        JdbcClient jdbcClient(DataSource dataSource) {
            return JdbcClient.create(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        CommentRepository commentRepository(JdbcTemplate jdbcTemplate) {
            return new CommentRepository(jdbcTemplate, new ObjectMapper().findAndRegisterModules());
        }
    }
}
