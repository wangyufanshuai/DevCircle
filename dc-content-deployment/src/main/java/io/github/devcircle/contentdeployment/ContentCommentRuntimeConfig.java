package io.github.devcircle.contentdeployment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Single activation boundary for the future in-process Comment runtime. */
@Configuration(proxyBeanMethods = false)
class ContentCommentRuntimeConfig {
    static final String UNSUPPORTED_MESSAGE = "Content Comment runtime cannot be enabled yet: "
            + "an isolated Comment DataSource, transaction manager, and Flyway owner are required";

    @Bean
    @ConditionalOnProperty(prefix = "devcircle.comment-runtime", name = "enabled", havingValue = "true")
    Object unsupportedCommentRuntimeGuard() {
        throw new IllegalStateException(UNSUPPORTED_MESSAGE);
    }
}
