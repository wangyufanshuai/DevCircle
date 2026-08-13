package io.github.devcircle.contentdeployment;

import com.zaxxer.hikari.HikariDataSource;
import io.github.devcircle.comment.application.CommentService;
import io.github.devcircle.comment.application.PostPublicationGateway;
import io.github.devcircle.comment.api.CreateCommentRequest;
import io.github.devcircle.comment.infrastructure.CommentRepository;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.content.application.PostService;
import io.github.devcircle.messaging.outbox.ReliableOutboxWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:content-embedded-comment;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.minimum-idle=0",
        "spring.flyway.enabled=false",
        "spring.kafka.bootstrap-servers=localhost:65535",
        "spring.kafka.listener.auto-startup=false",
        "devcircle.kafka.topics.enabled=false",
        "devcircle.outbox.enabled=false",
        "devcircle.outbox.initial-delay-ms=3600000",
        "devcircle.comment-runtime.enabled=true",
        "devcircle.comment-runtime.flyway-enabled=false",
        "devcircle.comment-runtime.outbox-enabled=true",
        "devcircle.comment-runtime.datasource.url=jdbc:h2:mem:comment-embedded;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "devcircle.comment-runtime.datasource.username=sa",
        "devcircle.comment-runtime.datasource.password=",
        "devcircle.comment-runtime.datasource.maximum-pool-size=3",
        "devcircle.comment-runtime.datasource.minimum-idle=0",
        "devcircle.content.aggregate-db-pool-ceiling=8",
        "devcircle.seed.theme-content.enabled=false",
        "management.tracing.enabled=false",
        "devcircle.s3.endpoint=http://localhost:19000",
        "devcircle.s3.presign-endpoint=http://localhost:19000",
        "devcircle.s3.bucket=content-runtime-test",
        "devcircle.s3.access-key=test-access",
        "devcircle.s3.secret-key=test-secret",
        "devcircle.s3.public-base-url=http://localhost:19000/content-runtime-test"
})
class ContentEmbeddedCommentRuntimeTest {
    @Autowired
    private ApplicationContext context;

    @Autowired
    @Qualifier("dataSource")
    private DataSource contentDataSource;

    @Autowired
    @Qualifier("commentDataSource")
    private DataSource commentDataSource;

    @Test
    void embedsCommentWithIsolatedPersistenceAndNoHttpPublicationHop() {
        assertThat(context.getBean(PostService.class)).isNotNull();
        assertThat(context.getBean(CommentService.class)).isNotNull();
        assertThat(context.getBean(CommentRepository.class)).isNotNull();
        assertThat(context.containsBean("commentController")).isTrue();
        assertThat(context.containsBean("postCommentController")).isTrue();
        assertThat(context.containsBean("inProcessPostPublicationGateway")).isTrue();
        assertThat(context.containsBean("httpPostPublicationGateway")).isFalse();
        assertThat(context.getBeansOfType(ReliableOutboxWorker.class)).containsOnlyKeys("commentOutboxWorker");
        assertThat(context.getBeansOfType(JdbcTemplate.class)).containsKeys("jdbcTemplate", "commentJdbcTemplate");
        assertThat(context.getBeansOfType(JdbcClient.class)).containsKeys("jdbcClient", "commentJdbcClient");
        assertThat(context.getBeansOfType(PlatformTransactionManager.class))
                .containsKeys("transactionManager", "commentTransactionManager");
        assertThat(contentDataSource).isNotSameAs(commentDataSource);
        assertThat(((HikariDataSource) contentDataSource).getMaximumPoolSize()).isEqualTo(5);
        assertThat(((HikariDataSource) commentDataSource).getMaximumPoolSize()).isEqualTo(3);
    }

    @Test
    void publicationGatewayReadsTheContentDatabaseInProcess() {
        JdbcClient content = context.getBean("jdbcClient", JdbcClient.class);
        content.sql("CREATE TABLE IF NOT EXISTS post (id BIGINT PRIMARY KEY, status VARCHAR(32) NOT NULL)").update();
        content.sql("MERGE INTO post KEY(id) VALUES (41, 'PUBLISHED'), (42, 'PENDING')").update();
        PostPublicationGateway gateway = context.getBean(PostPublicationGateway.class);

        gateway.requirePublished(41L);
        assertThatThrownBy(() -> gateway.requirePublished(42L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void commentWritesUseOnlyTheIsolatedCommentDatabase() {
        JdbcClient content = context.getBean("jdbcClient", JdbcClient.class);
        JdbcClient comment = context.getBean("commentJdbcClient", JdbcClient.class);
        content.sql("CREATE TABLE IF NOT EXISTS post (id BIGINT PRIMARY KEY, status VARCHAR(32) NOT NULL)").update();
        content.sql("MERGE INTO post KEY(id) VALUES (51, 'PUBLISHED')").update();
        createCommentTables(comment);

        context.getBean(CommentService.class).create(7L, "embedded-comment-create-51",
                new CreateCommentRequest(51L, null, "isolated persistence"));

        assertThat(comment.sql("SELECT COUNT(*) FROM comment WHERE post_id = 51")
                .query(Long.class).single()).isEqualTo(1L);
        assertThat(content.sql("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'comment'")
                .query(Long.class).single()).isZero();
    }

    private void createCommentTables(JdbcClient jdbc) {
        jdbc.sql("CREATE TABLE comment (id BIGINT AUTO_INCREMENT PRIMARY KEY, post_id BIGINT NOT NULL, "
                + "author_id BIGINT NOT NULL, parent_id BIGINT, root_id BIGINT, content VARCHAR(2000) NOT NULL, "
                + "status VARCHAR(32) NOT NULL, like_count BIGINT NOT NULL DEFAULT 0, "
                + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)").update();
        jdbc.sql("CREATE TABLE idempotency_record (idempotency_key VARCHAR(128) PRIMARY KEY, user_id BIGINT NOT NULL, "
                + "operation VARCHAR(64) NOT NULL, request_fingerprint CHAR(64) NOT NULL, resource_id BIGINT)").update();
        jdbc.sql("CREATE TABLE post_comment_counter (post_id BIGINT PRIMARY KEY, visible_count BIGINT NOT NULL, "
                + "projection_version BIGINT NOT NULL)").update();
        jdbc.sql("CREATE TABLE outbox_event (id BIGINT AUTO_INCREMENT PRIMARY KEY, event_id CHAR(36) UNIQUE NOT NULL, "
                + "topic VARCHAR(128) NOT NULL, aggregate_type VARCHAR(64) NOT NULL, aggregate_id VARCHAR(64) NOT NULL, "
                + "payload JSON NOT NULL, status VARCHAR(32) NOT NULL DEFAULT 'NEW', created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)")
                .update();
    }
}
