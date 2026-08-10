package io.github.devcircle.user.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.user.infrastructure.UserGraphRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class UserRuntimeSwitchTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
            .withBean(UserGraphRepository.class, () -> mock(UserGraphRepository.class))
            .withUserConfiguration(ConsumerConfiguration.class);

    @Test
    void userProjectionConsumerStartsByDefault() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(UserRegisteredConsumer.class));
    }

    @Test
    void legacyRollbackCanDisableTheEmbeddedProjectionConsumer() {
        contextRunner.withPropertyValues("devcircle.user-runtime.consumer-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(UserRegisteredConsumer.class));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(UserRegisteredConsumer.class)
    static class ConsumerConfiguration {
    }
}
