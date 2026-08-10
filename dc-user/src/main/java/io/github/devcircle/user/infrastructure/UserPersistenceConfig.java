package io.github.devcircle.user.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/** Standalone defaults that an aggregate deployment can replace by bean name. */
@Configuration(proxyBeanMethods = false)
public class UserPersistenceConfig {
    @Bean("userJdbcClient")
    @ConditionalOnMissingBean(name = "userJdbcClient")
    JdbcClient userJdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean("userTransactionManager")
    @ConditionalOnMissingBean(name = "userTransactionManager")
    PlatformTransactionManager userTransactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
