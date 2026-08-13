package io.github.devcircle.contentdeployment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.github.devcircle.comment.api.CommentController;
import io.github.devcircle.comment.api.PostCommentController;
import io.github.devcircle.comment.application.CommentService;
import io.github.devcircle.comment.application.PostPublicationGateway;
import io.github.devcircle.comment.infrastructure.CommentRepository;
import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.messaging.outbox.ReliableOutboxWorker;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Isolated, default-off Comment runtime for the Content aggregate. The
 * standalone service remains the production owner until a separate cutover.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "devcircle.comment-runtime", name = "enabled", havingValue = "true")
class ContentCommentRuntimeConfig {
    static final String MIGRATION_LOCATION = "classpath:db/comment-migration";
    static final int DEFAULT_AGGREGATE_POOL_CEILING = 8;

    @Bean("commentDataSourceProperties")
    @ConfigurationProperties("devcircle.comment-runtime.datasource")
    CommentDataSourceProperties commentDataSourceProperties() {
        return new CommentDataSourceProperties();
    }

    @Bean(name = "commentDataSource", destroyMethod = "close")
    DataSource commentDataSource(
            @Qualifier("commentDataSourceProperties") CommentDataSourceProperties properties) {
        properties.validate();
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("content-comment");
        dataSource.setJdbcUrl(properties.getUrl());
        dataSource.setUsername(properties.getUsername());
        dataSource.setPassword(properties.getPassword());
        dataSource.setMaximumPoolSize(properties.getMaximumPoolSize());
        dataSource.setMinimumIdle(properties.getMinimumIdle());
        dataSource.setConnectionTimeout(properties.getConnectionTimeout());
        return dataSource;
    }

    @Bean("commentJdbcTemplate")
    JdbcTemplate commentJdbcTemplate(@Qualifier("commentDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean("commentJdbcClient")
    JdbcClient commentJdbcClient(@Qualifier("commentDataSource") DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean("commentTransactionManager")
    PlatformTransactionManager commentTransactionManager(
            @Qualifier("commentDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean("commentFlyway")
    @ConditionalOnProperty(prefix = "devcircle.comment-runtime", name = "flyway-enabled",
            havingValue = "true", matchIfMissing = true)
    Flyway commentFlyway(@Qualifier("commentDataSource") DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations(MIGRATION_LOCATION).load();
    }

    @Bean("commentFlywayInitializer")
    @ConditionalOnProperty(prefix = "devcircle.comment-runtime", name = "flyway-enabled",
            havingValue = "true", matchIfMissing = true)
    FlywayMigrationInitializer commentFlywayInitializer(@Qualifier("commentFlyway") Flyway flyway) {
        FlywayMigrationInitializer initializer = new FlywayMigrationInitializer(flyway);
        initializer.setOrder(2);
        return initializer;
    }

    @Bean
    PostPublicationGateway inProcessPostPublicationGateway(
            @Qualifier("jdbcClient") JdbcClient contentJdbcClient) {
        return postId -> {
            Integer published = contentJdbcClient.sql(
                            "SELECT COUNT(*) FROM post WHERE id = :postId AND status = 'PUBLISHED'")
                    .param("postId", postId)
                    .query(Integer.class)
                    .single();
            if (published == null || published != 1) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "内容不存在或尚未发布");
            }
        };
    }

    @Bean
    CommentRepository commentRepository(
            @Qualifier("commentJdbcTemplate") JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper) {
        return new CommentRepository(jdbcTemplate, objectMapper);
    }

    @Bean
    CommentService commentService(CommentRepository repository,
                                  PostPublicationGateway publicationGateway) {
        return new CommentService(repository, publicationGateway);
    }

    @Bean
    CommentController commentController(CommentService service) {
        return new CommentController(service);
    }

    @Bean
    PostCommentController postCommentController(CommentService service) {
        return new PostCommentController(service);
    }

    @Bean("commentOutboxWorker")
    @ConditionalOnProperty(prefix = "devcircle.comment-runtime", name = "outbox-enabled",
            havingValue = "true", matchIfMissing = true)
    ReliableOutboxWorker commentOutboxWorker(
            @Qualifier("commentJdbcClient") JdbcClient jdbcClient,
            KafkaTemplate<String, String> kafkaTemplate) {
        return new ReliableOutboxWorker(jdbcClient, kafkaTemplate, 50, 8, 15_000L);
    }

    @Bean
    Object commentAggregatePoolBudgetGuard(
            @Qualifier("dataSource") DataSource contentDataSource,
            @Qualifier("commentDataSource") DataSource commentDataSource,
            org.springframework.core.env.Environment environment) {
        int ceiling = environment.getProperty("devcircle.content.aggregate-db-pool-ceiling",
                Integer.class, DEFAULT_AGGREGATE_POOL_CEILING);
        int total = maximumPoolSize(contentDataSource) + maximumPoolSize(commentDataSource);
        if (ceiling < 2 || total > ceiling) {
            throw new IllegalStateException("Content and Comment database pool ceiling exceeded: "
                    + total + " > " + ceiling);
        }
        return new Object();
    }

    private int maximumPoolSize(DataSource dataSource) {
        if (!(dataSource instanceof HikariDataSource hikari)) {
            throw new IllegalStateException("Content aggregate requires HikariDataSource pool accounting");
        }
        return hikari.getMaximumPoolSize();
    }

    static final class CommentDataSourceProperties {
        private String url;
        private String username;
        private String password = "";
        private int maximumPoolSize = 3;
        private int minimumIdle;
        private long connectionTimeout = 3000;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public int getMaximumPoolSize() { return maximumPoolSize; }
        public void setMaximumPoolSize(int maximumPoolSize) { this.maximumPoolSize = maximumPoolSize; }
        public int getMinimumIdle() { return minimumIdle; }
        public void setMinimumIdle(int minimumIdle) { this.minimumIdle = minimumIdle; }
        public long getConnectionTimeout() { return connectionTimeout; }
        public void setConnectionTimeout(long connectionTimeout) { this.connectionTimeout = connectionTimeout; }

        void validate() {
            if (url == null || url.isBlank()) throw new IllegalStateException("Comment JDBC URL is required");
            if (username == null || username.isBlank()) throw new IllegalStateException("Comment JDBC username is required");
            if (maximumPoolSize < 1) throw new IllegalStateException("Comment maximumPoolSize must be at least 1");
            if (minimumIdle < 0 || minimumIdle > maximumPoolSize) {
                throw new IllegalStateException("Comment minimumIdle must be between 0 and maximumPoolSize");
            }
            if (connectionTimeout < 250) {
                throw new IllegalStateException("Comment connectionTimeout must be at least 250ms");
            }
        }
    }
}
