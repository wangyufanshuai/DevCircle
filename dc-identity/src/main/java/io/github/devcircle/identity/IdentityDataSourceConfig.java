package io.github.devcircle.identity;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/** Owns the auth and user persistence resources while both schemas share one deployment. */
@Configuration(proxyBeanMethods = false)
class IdentityDataSourceConfig {
    static final String AUTH_MIGRATION_LOCATION = "classpath:db/migration";
    static final String USER_MIGRATION_LOCATION = "classpath:db/user-migration";

    @Bean("authDataSourceProperties")
    @ConfigurationProperties("devcircle.identity.auth-datasource")
    IdentityDataSourceProperties authDataSourceProperties() {
        return new IdentityDataSourceProperties();
    }

    @Bean("userDataSourceProperties")
    @ConfigurationProperties("devcircle.user-runtime.datasource")
    IdentityDataSourceProperties userDataSourceProperties() {
        return new IdentityDataSourceProperties();
    }

    @Bean(name = "dataSource", destroyMethod = "close")
    @Primary
    DataSource authDataSource(
            @Qualifier("authDataSourceProperties") IdentityDataSourceProperties properties) {
        return dataSource("identity-auth", properties);
    }

    @Bean(name = "userDataSource", destroyMethod = "close")
    DataSource userDataSource(
            @Qualifier("userDataSourceProperties") IdentityDataSourceProperties properties) {
        return dataSource("identity-user", properties);
    }

    @Bean
    @Primary
    JdbcClient jdbcClient(@Qualifier("dataSource") DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean("userJdbcClient")
    JdbcClient userJdbcClient(@Qualifier("userDataSource") DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean
    @Primary
    PlatformTransactionManager transactionManager(@Qualifier("dataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean("userTransactionManager")
    PlatformTransactionManager userTransactionManager(@Qualifier("userDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean(name = "flyway")
    @Primary
    @ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true", matchIfMissing = true)
    Flyway authFlyway(
            @Qualifier("dataSource") DataSource dataSource,
            @Value("${spring.flyway.locations:" + AUTH_MIGRATION_LOCATION + "}") String migrationLocations,
            ObjectProvider<FlywayConfigurationCustomizer> customizers) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations(migrationLocations);
        customizers.orderedStream().forEach(customizer -> customizer.customize(configuration));
        return configuration.load();
    }

    @Bean("flywayInitializer")
    @ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true", matchIfMissing = true)
    FlywayMigrationInitializer authFlywayInitializer(@Qualifier("flyway") Flyway flyway) {
        FlywayMigrationInitializer initializer = new FlywayMigrationInitializer(flyway);
        initializer.setOrder(0);
        return initializer;
    }

    @Bean("userFlyway")
    @ConditionalOnProperty(name = "devcircle.user-runtime.flyway-enabled", havingValue = "true", matchIfMissing = true)
    Flyway userFlyway(
            @Qualifier("userDataSource") DataSource dataSource,
            @Value("${devcircle.user-runtime.flyway-locations:" + USER_MIGRATION_LOCATION + "}")
            String migrationLocations) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(migrationLocations)
                .load();
    }

    @Bean("userFlywayInitializer")
    @ConditionalOnProperty(name = "devcircle.user-runtime.flyway-enabled", havingValue = "true", matchIfMissing = true)
    FlywayMigrationInitializer userFlywayInitializer(@Qualifier("userFlyway") Flyway userFlyway) {
        FlywayMigrationInitializer initializer = new FlywayMigrationInitializer(userFlyway);
        initializer.setOrder(1);
        return initializer;
    }

    private HikariDataSource dataSource(String poolName, IdentityDataSourceProperties properties) {
        properties.validate(poolName);
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName(poolName);
        dataSource.setJdbcUrl(properties.getUrl());
        dataSource.setUsername(properties.getUsername());
        dataSource.setPassword(properties.getPassword());
        dataSource.setMaximumPoolSize(properties.getMaximumPoolSize());
        dataSource.setMinimumIdle(properties.getMinimumIdle());
        dataSource.setConnectionTimeout(properties.getConnectionTimeout());
        return dataSource;
    }

    static final class IdentityDataSourceProperties {
        private String url;
        private String username;
        private String password = "";
        private int maximumPoolSize = 6;
        private int minimumIdle = 1;
        private long connectionTimeout = 3000;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public int getMaximumPoolSize() {
            return maximumPoolSize;
        }

        public void setMaximumPoolSize(int maximumPoolSize) {
            this.maximumPoolSize = maximumPoolSize;
        }

        public int getMinimumIdle() {
            return minimumIdle;
        }

        public void setMinimumIdle(int minimumIdle) {
            this.minimumIdle = minimumIdle;
        }

        public long getConnectionTimeout() {
            return connectionTimeout;
        }

        public void setConnectionTimeout(long connectionTimeout) {
            this.connectionTimeout = connectionTimeout;
        }

        void validate(String poolName) {
            if (url == null || url.isBlank()) {
                throw new IllegalStateException(poolName + " JDBC URL is required");
            }
            if (username == null || username.isBlank()) {
                throw new IllegalStateException(poolName + " JDBC username is required");
            }
            if (maximumPoolSize < 1) {
                throw new IllegalStateException(poolName + " maximumPoolSize must be at least 1");
            }
            if (minimumIdle < 0 || minimumIdle > maximumPoolSize) {
                throw new IllegalStateException(poolName + " minimumIdle must be between 0 and maximumPoolSize");
            }
            if (connectionTimeout < 250) {
                throw new IllegalStateException(poolName + " connectionTimeout must be at least 250ms");
            }
        }
    }
}
