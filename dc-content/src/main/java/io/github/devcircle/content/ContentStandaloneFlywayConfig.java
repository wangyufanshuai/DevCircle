package io.github.devcircle.content;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;

import javax.sql.DataSource;

/**
 * Keeps the legacy standalone Content deployment compatible with the shared
 * media schema. The aggregate deployment owns the equivalent configuration in
 * dc-content-deployment and does not import this class.
 */
@ConditionalOnProperty(name = "devcircle.standalone-flyway-enabled", havingValue = "true", matchIfMissing = true)
class ContentStandaloneFlywayConfig {
    static final String CONTENT_LOCATION = "classpath:db/migration";
    static final String FILE_LOCATION = "classpath:db/file-migration";

    @Bean("flyway")
    Flyway contentFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(CONTENT_LOCATION)
                .table("flyway_schema_history")
                .load();
    }

    @Bean("flywayInitializer")
    FlywayMigrationInitializer contentFlywayInitializer(@Qualifier("flyway") Flyway flyway) {
        FlywayMigrationInitializer initializer = new FlywayMigrationInitializer(flyway);
        initializer.setOrder(0);
        return initializer;
    }

    @Bean("fileFlyway")
    Flyway fileFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(FILE_LOCATION)
                .table("flyway_schema_history_file")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load();
    }

    @Bean("fileFlywayInitializer")
    @DependsOn("flywayInitializer")
    FlywayMigrationInitializer fileFlywayInitializer(@Qualifier("fileFlyway") Flyway flyway) {
        FlywayMigrationInitializer initializer = new FlywayMigrationInitializer(flyway);
        initializer.setOrder(1);
        return initializer;
    }
}
