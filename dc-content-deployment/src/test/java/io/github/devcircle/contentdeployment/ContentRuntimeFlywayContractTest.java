package io.github.devcircle.contentdeployment;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ContentRuntimeFlywayContractTest {
    @Test
    void keepsContentAndFileHistoriesSeparateAndOrdered() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Flyway content = new ContentRuntimeFlywayConfig().contentFlyway(dataSource);
        Flyway file = new ContentRuntimeFlywayConfig().fileFlyway(dataSource);

        assertThat(content.getConfiguration().getTable())
                .isEqualTo(ContentRuntimeFlywayConfig.CONTENT_HISTORY_TABLE);
        assertThat(file.getConfiguration().getTable())
                .isEqualTo(ContentRuntimeFlywayConfig.FILE_HISTORY_TABLE);
        assertThat(content.getConfiguration().getLocations())
                .extracting(Object::toString).containsExactly(ContentRuntimeFlywayConfig.CONTENT_LOCATION);
        assertThat(file.getConfiguration().getLocations())
                .extracting(Object::toString).containsExactly(ContentRuntimeFlywayConfig.FILE_LOCATION);
        assertThat(ContentRuntimeFlywayConfig.class.getDeclaredMethod("fileFlywayInitializer", Flyway.class)
                .getAnnotation(org.springframework.context.annotation.DependsOn.class).value())
                .contains("contentFlywayInitializer");
    }

    @Test
    void publishesOnlyTheRelocatedFileMigrations() {
        ClassLoader loader = getClass().getClassLoader();
        assertThat(loader.getResource("db/file-migration/V1__file_schema.sql")).isNotNull();
        assertThat(loader.getResource("db/file-migration/V2__presigned_upload_workflow.sql")).isNotNull();
        assertThat(loader.getResource("db/file-migration/V3__bind_file_idempotency_payloads.sql")).isNotNull();
        assertThat(loader.getResource("db/migration/V1__file_schema.sql")).isNull();
    }

    @Test
    void contentFlywayLocationResolvesOnlyTheTwentyThreeContentMigrations() throws IOException {
        DataSource dataSource = mock(DataSource.class);
        Flyway content = new ContentRuntimeFlywayConfig().contentFlyway(dataSource);
        String configuredLocation = content.getConfiguration().getLocations()[0].toString();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

        List<String> contentMigrations = filenames(resolver.getResources(
                configuredLocation.replace("classpath:", "classpath*:") + "/*.sql"));
        List<String> commentMigrations = filenames(resolver.getResources(
                "classpath*:db/comment-migration/*.sql"));

        assertThat(configuredLocation).isEqualTo(ContentRuntimeFlywayConfig.CONTENT_LOCATION);
        assertThat(contentMigrations)
                .hasSize(23)
                .contains("V1__content_schema.sql", "V23__kafka_dlt_replay_audit.sql")
                .noneMatch(name -> name.contains("comment_schema")
                        || name.contains("comment_audit_reason")
                        || name.contains("comment_idempotency_payloads")
                        || name.contains("comment_counter_snapshot")
                        || name.contains("comment_threads"));
        assertThat(commentMigrations).containsExactly(
                "V1__comment_schema.sql",
                "V2__reliable_outbox.sql",
                "V3__outbox_aggregate_ordering.sql",
                "V4__expand_comment_audit_reason.sql",
                "V5__bind_comment_idempotency_payloads.sql",
                "V6__comment_counter_snapshot.sql",
                "V7__optimize_comment_threads.sql");
        assertThat(getClass().getClassLoader().getResource("db/migration/V1__comment_schema.sql")).isNull();
        assertThat(getClass().getClassLoader().getResource(
                "db/migration/V7__optimize_comment_threads.sql")).isNull();
    }

    @Test
    void embeddedCommentFlywayUsesOnlyThePreservedCommentHistory() {
        DataSource dataSource = mock(DataSource.class);
        Flyway comment = new ContentCommentRuntimeConfig().commentFlyway(dataSource);

        assertThat(comment.getConfiguration().getLocations())
                .extracting(Object::toString)
                .containsExactly(ContentCommentRuntimeConfig.MIGRATION_LOCATION);
        assertThat(comment.getConfiguration().getTable()).isEqualTo("flyway_schema_history");
    }

    private List<String> filenames(Resource[] resources) {
        return Arrays.stream(resources)
                .map(Resource::getFilename)
                .sorted()
                .toList();
    }
}
