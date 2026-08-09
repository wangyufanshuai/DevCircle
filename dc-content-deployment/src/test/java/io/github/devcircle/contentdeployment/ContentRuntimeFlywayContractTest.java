package io.github.devcircle.contentdeployment;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

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
}
