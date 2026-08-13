package io.github.devcircle.contentdeployment;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/** Keeps Content's primary JDBC beans stable when optional domain data sources are enabled. */
@Configuration(proxyBeanMethods = false)
class ContentDataAccessConfig {
    @Bean("contentDataSourceProperties")
    @ConfigurationProperties("spring.datasource")
    DataSourceProperties contentDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "dataSource", destroyMethod = "close")
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource dataSource(
            @Qualifier("contentDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    @Primary
    JdbcTemplate jdbcTemplate(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    @Primary
    JdbcClient jdbcClient(@Qualifier("dataSource") DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean
    @Primary
    PlatformTransactionManager transactionManager(@Qualifier("dataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
