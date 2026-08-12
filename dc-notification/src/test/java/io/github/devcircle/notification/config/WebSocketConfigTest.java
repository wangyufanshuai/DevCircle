package io.github.devcircle.notification.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebSocketConfigTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(WebSocketConfig.class);

    @Test
    void standaloneRuntimeDefaultsToRestOnly() throws Exception {
        try (InputStream resource = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            assertThat(resource).isNotNull();
            String configuration = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(configuration)
                    .contains("api-enabled: true")
                    .contains("websocket-enabled: false");
        }
    }

    @Test
    void preInterceptorFrameworkLoggingCannotEmitRawStompHeaders() throws Exception {
        MutablePropertySources sources = new MutablePropertySources();
        new YamlPropertySourceLoader().load("notification-application",
                        new ClassPathResource("application.yml"))
                .forEach(sources::addLast);
        PropertySourcesPropertyResolver properties = new PropertySourcesPropertyResolver(sources);

        assertThat(properties.getProperty(
                "logging.level.org.springframework.web.socket.handler.LoggingWebSocketHandlerDecorator"))
                .isEqualTo("INFO");
        assertThat(properties.getProperty(
                "logging.level.org.springframework.web.socket.messaging.StompSubProtocolHandler"))
                .isEqualTo("OFF");
        assertThat(properties.getProperty(
                "logging.level.org.springframework.messaging.simp.stomp.StompDecoder"))
                .isEqualTo("INFO");
        assertThat(properties.getProperty("logging.level.org.springframework.web.SimpLogging"))
                .isEqualTo("INFO");
    }

    @Test
    void enabledInfrastructureFailsFastWithoutAnAccessTokenVerifier() {
        contextRunner
                .withPropertyValues("devcircle.notification-runtime.websocket-enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(NoSuchBeanDefinitionException.class)
                            .hasMessageContaining("NotificationAccessTokenVerifier");
                });
    }

    @Test
    void secureDefaultOffDoesNotRequireAWebSocketVerifier() {
        contextRunner
                .withPropertyValues("devcircle.notification-runtime.websocket-enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(WebSocketConfig.class);
                });
    }

    @Test
    void acceptsAConcreteBrowserOrigin() {
        assertThatCode(() -> new WebSocketConfig("https://mysticverse.example.com/", token ->
                new NotificationAccessTokenVerifier.VerifiedAccessToken(
                        7L, Instant.now().plusSeconds(60))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsWildcardOrNonOriginValues() {
        assertThatThrownBy(() -> new WebSocketConfig("*", token ->
                new NotificationAccessTokenVerifier.VerifiedAccessToken(
                        7L, Instant.now().plusSeconds(60))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("concrete HTTP(S) origin");
        assertThatThrownBy(() -> new WebSocketConfig(
                "https://mysticverse.example.com/private", token ->
                        new NotificationAccessTokenVerifier.VerifiedAccessToken(
                                7L, Instant.now().plusSeconds(60))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("concrete HTTP(S) origin");
    }
}
