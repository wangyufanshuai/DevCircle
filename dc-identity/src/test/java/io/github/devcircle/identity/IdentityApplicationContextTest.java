package io.github.devcircle.identity;

import io.github.devcircle.auth.application.AuthService;
import io.github.devcircle.auth.application.JwtService;
import io.github.devcircle.messaging.outbox.OutboxStatusService;
import io.github.devcircle.notification.config.NotificationAccessTokenVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.handler;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "devcircle.identity.auth-datasource.url=jdbc:h2:mem:identity-context;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "devcircle.identity.auth-datasource.username=sa",
        "devcircle.identity.auth-datasource.password=",
        "spring.flyway.enabled=false",
        "devcircle.user-runtime.flyway-enabled=false",
        "devcircle.interaction-runtime.flyway-enabled=false",
        "devcircle.notification-runtime.flyway-enabled=false",
        "devcircle.user.profile-backfill.enabled=false",
        "devcircle.outbox.enabled=false",
        "devcircle.kafka.topics.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "management.tracing.enabled=false"
})
@AutoConfigureMockMvc
class IdentityApplicationContextTest {
    @Autowired
    private ApplicationContext context;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Test
    void embedsDomainComponentsWithoutLoadingLegacyApplicationsOrGatewayRoutes() {
        assertThat(context.getBean(AuthService.class)).isNotNull();
        assertThat(context.getBeansOfType(OutboxStatusService.class))
                .containsOnlyKeys("outboxStatusService");
        assertThat(context.getBean(IdentityUserOutboxStatus.class)).isNotNull();
        assertThat(context.getBean(IdentityInteractionOutboxStatus.class)).isNotNull();
        assertThat(context.containsBean("userReliableOutboxWorker")).isFalse();
        assertThat(context.containsBean("interactionReliableOutboxWorker")).isFalse();
        assertThat(context.containsBean("notificationEventConsumer")).isFalse();
        assertThat(context.containsBean("notificationFlyway")).isFalse();
        assertThat(context.containsBean("notificationController")).isFalse();
        assertThat(context.containsBean("notificationKafkaTopics")).isFalse();
        assertThat(context.containsBean("webSocketConfig")).isFalse();
        assertThat(context.getBean(NotificationAccessTokenVerifier.class)).isNotNull();
        assertThat(context.containsBean("authApplication")).isFalse();
        assertThat(Arrays.stream(context.getBeanDefinitionNames()))
                .noneMatch(name -> name.toLowerCase().contains("routedefinition"));
        assertThat(getClass().getClassLoader().getResource("db/migration/V1__auth_schema.sql")).isNotNull();
        assertThat(context.getEnvironment().getProperty("spring.application.name")).isEqualTo("dc-identity");
        assertThat(context.getEnvironment().getProperty("devcircle.outbox.service-name")).isEqualTo("dc-auth");
    }

    @Test
    void embeddedNotificationRuntimeCannotLogRawPreInterceptorStompFrames() {
        assertThat(context.getEnvironment().getProperty(
                "logging.level.org.springframework.web.socket.handler.LoggingWebSocketHandlerDecorator"))
                .isEqualTo("INFO");
        assertThat(context.getEnvironment().getProperty(
                "logging.level.org.springframework.web.socket.messaging.StompSubProtocolHandler"))
                .isEqualTo("OFF");
        assertThat(context.getEnvironment().getProperty(
                "logging.level.org.springframework.messaging.simp.stomp.StompDecoder"))
                .isEqualTo("INFO");
        assertThat(context.getEnvironment().getProperty("logging.level.org.springframework.web.SimpLogging"))
                .isEqualTo("INFO");
    }

    @Test
    void dashboardRequiresAValidBearerToken() throws Exception {
        assertUnauthorized("identity-no-token", get("/api/me/dashboard")
                .header("X-Trace-Id", "identity-no-token"));
        assertUnauthorized("identity-forged-user", get("/api/me/dashboard")
                .header("X-Trace-Id", "identity-forged-user")
                .header("X-User-Id", "2"));
        assertUnauthorized("identity-invalid-bearer", get("/api/me/dashboard")
                .header("X-Trace-Id", "identity-invalid-bearer")
                .header("Authorization", "Bearer invalid-token"));

        String token = jwtService.issueAccess(2L, "Observer", List.of("USER"), Instant.now().plusSeconds(60));
        mockMvc.perform(get("/api/me/dashboard").header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
    }

    @Test
    void exposesPrometheusAnonymouslyWithoutOpeningOtherManagementOrUserRoutes() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", startsWith("text/plain")));

        assertUnauthorized("identity-info-private", get("/actuator/info")
                .header("X-Trace-Id", "identity-info-private"));
        assertUnauthorized("identity-dashboard-private", get("/api/me/dashboard")
                .header("X-Trace-Id", "identity-dashboard-private"));
    }

    private void assertUnauthorized(String traceId,
                                    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(header().string("X-Trace-Id", traceId))
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("请先登录后再继续"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.meta").isEmpty())
                .andExpect(jsonPath("$.traceId").value(traceId))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void authenticationRouteIsHandledInsideIdentity() throws Exception {
        mockMvc.perform(get("/api/auth/oauth2/github/start"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OAUTH_NOT_CONFIGURED"));
    }

    @Test
    void onlyTheExactNotificationHandshakePathBypassesHttpBearerAuthentication() throws Exception {
        mockMvc.perform(get("/ws/notifications"))
                .andExpect(handler().handlerType(ResourceHttpRequestHandler.class))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
        assertUnauthorized("identity-ws-child-private", get("/ws/notifications/other")
                .header("X-Trace-Id", "identity-ws-child-private"));
        assertUnauthorized("identity-ws-neighbor-private", get("/ws/notification")
                .header("X-Trace-Id", "identity-ws-neighbor-private"));
    }
}
