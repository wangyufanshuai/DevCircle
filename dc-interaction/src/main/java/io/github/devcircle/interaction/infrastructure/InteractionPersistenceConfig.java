package io.github.devcircle.interaction.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/** Standalone persistence defaults that an aggregate deployment replaces by bean name. */
@Configuration(proxyBeanMethods = false)
public class InteractionPersistenceConfig {
    @Bean("interactionJdbcClient")
    @ConditionalOnMissingBean(name = "interactionJdbcClient")
    JdbcClient interactionJdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean("interactionTransactionManager")
    @ConditionalOnMissingBean(name = "interactionTransactionManager")
    PlatformTransactionManager interactionTransactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
