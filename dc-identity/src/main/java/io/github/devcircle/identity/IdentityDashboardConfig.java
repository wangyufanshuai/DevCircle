package io.github.devcircle.identity;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration(proxyBeanMethods = false)
class IdentityDashboardConfig {
    @Bean(name = "dashboardExecutor", destroyMethod = "close")
    ExecutorService dashboardExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
