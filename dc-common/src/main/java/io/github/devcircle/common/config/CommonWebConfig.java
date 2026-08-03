package io.github.devcircle.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.error.GlobalExceptionHandler;
import io.github.devcircle.common.http.BoundedRestClientFactory;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class CommonWebConfig {
    @Bean
    GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    @Bean
    TraceContextFilter traceContextFilter() {
        return new TraceContextFilter();
    }

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    RestClientCustomizer boundedRestClientCustomizer() {
        return BoundedRestClientFactory::customize;
    }
}
