package io.github.devcircle.comment.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/** Gives the standalone service the same transaction-manager name used by embedders. */
@Configuration(proxyBeanMethods = false)
class CommentPersistenceConfig {
    @Bean("commentTransactionManager")
    @ConditionalOnMissingBean(name = "commentTransactionManager")
    PlatformTransactionManager commentTransactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
