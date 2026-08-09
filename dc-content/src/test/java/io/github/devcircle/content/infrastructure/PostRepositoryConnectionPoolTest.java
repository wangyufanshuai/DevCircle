package io.github.devcircle.content.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class PostRepositoryConnectionPoolTest {

    @Test
    void listLoadsMediaWithoutRequestingANestedConnection() {
        try (HikariDataSource dataSource = dataSource()) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            createSchema(jdbc);
            jdbc.update("INSERT INTO post(id, author_id, title, status) VALUES (1, 7, 'Pool-safe post', 'PUBLISHED')");
            jdbc.update("INSERT INTO post_content(post_id, markdown) VALUES (1, 'Body')");
            jdbc.update("""
                    INSERT INTO media_asset(id, object_key, url, content_type, size_bytes, status)
                    VALUES (11, 'published/cover.webp', '/media/11', 'image/webp', 1024, 'APPROVED')
                    """);
            jdbc.update("INSERT INTO post_media(post_id, media_id, sort_order) VALUES (1, 11, 0)");
            ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
            PostQueryRepository repository = new PostQueryRepository(jdbc, objectMapper);

            var posts = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> repository.list(1, 20));

            assertThat(posts).singleElement().satisfies(post -> {
                assertThat(post.id()).isEqualTo(1L);
                assertThat(post.mediaAssets()).singleElement().satisfies(media ->
                        assertThat(media.objectKey()).isEqualTo("published/cover.webp"));
            });
        }
    }

    private HikariDataSource dataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:h2:mem:content-pool-regression;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUsername("sa");
        dataSource.setMaximumPoolSize(1);
        dataSource.setMinimumIdle(1);
        dataSource.setConnectionTimeout(250);
        return dataSource;
    }

    private void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("""
                CREATE TABLE post (
                    id BIGINT PRIMARY KEY,
                    author_id BIGINT NOT NULL,
                    title VARCHAR(160) NOT NULL,
                    type VARCHAR(32) NOT NULL DEFAULT 'TEXT',
                    theme VARCHAR(32) NOT NULL DEFAULT 'DEDUCTION',
                    content_kind VARCHAR(48) NOT NULL DEFAULT 'CASE_FILE',
                    difficulty VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',
                    solvable BOOLEAN NOT NULL DEFAULT FALSE,
                    solved_count BIGINT NOT NULL DEFAULT 0,
                    status VARCHAR(32) NOT NULL,
                    like_count BIGINT NOT NULL DEFAULT 0,
                    comment_count BIGINT NOT NULL DEFAULT 0,
                    view_count BIGINT NOT NULL DEFAULT 0,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """);
        jdbc.execute("CREATE TABLE post_content(post_id BIGINT PRIMARY KEY, markdown CLOB NOT NULL)");
        jdbc.execute("CREATE TABLE tag(id BIGINT PRIMARY KEY, name VARCHAR(64) NOT NULL)");
        jdbc.execute("CREATE TABLE post_tag(post_id BIGINT NOT NULL, tag_id BIGINT NOT NULL)");
        jdbc.execute("""
                CREATE TABLE media_asset (
                    id BIGINT PRIMARY KEY,
                    object_key VARCHAR(512) NOT NULL,
                    url VARCHAR(1024) NOT NULL,
                    content_type VARCHAR(128) NOT NULL,
                    size_bytes BIGINT NOT NULL,
                    width INT NULL,
                    height INT NULL,
                    duration_seconds INT NULL,
                    status VARCHAR(32) NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE post_media (
                    post_id BIGINT NOT NULL,
                    media_id BIGINT NOT NULL,
                    sort_order INT NOT NULL DEFAULT 0,
                    PRIMARY KEY(post_id, media_id))
                """);
    }
}
