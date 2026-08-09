package io.github.devcircle.contentdeployment;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration(proxyBeanMethods = false)
class ContentCompositionConfig {
    @Bean(name = "contentCompositionExecutor", destroyMethod = "close")
    ExecutorService contentCompositionExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
