package io.github.devcircle.contentdeployment;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import javax.sql.DataSource;

/**
 * Preserves the two existing Flyway histories while both domains share the
 * dc_content datasource. Content always migrates before media.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true", matchIfMissing = true)
public class ContentRuntimeFlywayConfig {
    static final String CONTENT_LOCATION = "classpath:db/migration";
    static final String FILE_LOCATION = "classpath:db/file-migration";
    static final String CONTENT_HISTORY_TABLE = "flyway_schema_history";
    static final String FILE_HISTORY_TABLE = "flyway_schema_history_file";

    @Bean("contentFlyway")
    Flyway contentFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(CONTENT_LOCATION)
                .table(CONTENT_HISTORY_TABLE)
                .load();
    }

    @Bean("contentFlywayInitializer")
    FlywayMigrationInitializer contentFlywayInitializer(
            @Qualifier("contentFlyway") Flyway contentFlyway) {
        FlywayMigrationInitializer initializer = new FlywayMigrationInitializer(contentFlyway);
        initializer.setOrder(0);
        return initializer;
    }

    @Bean("fileFlyway")
    Flyway fileFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(FILE_LOCATION)
                .table(FILE_HISTORY_TABLE)
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load();
    }

    @Bean("fileFlywayInitializer")
    @DependsOn("contentFlywayInitializer")
    FlywayMigrationInitializer fileFlywayInitializer(
            @Qualifier("fileFlyway") Flyway fileFlyway) {
        FlywayMigrationInitializer initializer = new FlywayMigrationInitializer(fileFlyway);
        initializer.setOrder(1);
        return initializer;
    }
}
