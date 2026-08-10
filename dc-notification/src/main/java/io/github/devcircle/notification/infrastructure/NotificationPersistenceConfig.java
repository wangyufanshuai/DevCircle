package io.github.devcircle.notification.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/** Standalone persistence defaults that an aggregate deployment replaces by bean name. */
@Configuration(proxyBeanMethods = false)
public class NotificationPersistenceConfig {
    @Bean("notificationJdbcClient")
    @ConditionalOnMissingBean(name = "notificationJdbcClient")
    JdbcClient notificationJdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean("notificationTransactionManager")
    @ConditionalOnMissingBean(name = "notificationTransactionManager")
    PlatformTransactionManager notificationTransactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
