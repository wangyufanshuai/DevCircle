package io.github.devcircle.identity;

import com.zaxxer.hikari.HikariDataSource;
import io.github.devcircle.auth.application.JwtService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "devcircle.identity.auth-datasource.url=jdbc:h2:mem:identity-notification-auth;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "devcircle.identity.auth-datasource.username=sa",
        "devcircle.identity.auth-datasource.password=",
        "devcircle.identity.auth-datasource.maximum-pool-size=2",
        "devcircle.identity.auth-datasource.minimum-idle=0",
        "spring.flyway.enabled=true",
        "devcircle.user-runtime.flyway-enabled=false",
        "devcircle.interaction-runtime.flyway-enabled=false",
        "devcircle.notification-runtime.datasource.url=jdbc:h2:mem:identity-notification-domain;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "devcircle.notification-runtime.datasource.username=sa",
        "devcircle.notification-runtime.datasource.password=",
        "devcircle.notification-runtime.datasource.maximum-pool-size=2",
        "devcircle.notification-runtime.datasource.minimum-idle=0",
        "devcircle.notification-runtime.flyway-enabled=true",
        "devcircle.notification-runtime.flyway-locations=classpath:db/identity-notification-test-migration",
        "devcircle.notification-runtime.api-enabled=true",
        "devcircle.notification-runtime.consumer-enabled=true",
        "devcircle.notification-runtime.topic-admin-enabled=true",
        "devcircle.notification-runtime.websocket-enabled=true",
        "devcircle.user.profile-backfill.enabled=false",
        "devcircle.outbox.enabled=false",
        "devcircle.kafka.topics.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "management.tracing.enabled=false"
})
@AutoConfigureMockMvc
@Import(IdentityEmbeddedAuthWriteTest.H2AuthMigrationConfig.class)
class IdentityEmbeddedNotificationRuntimeTest {
    @Autowired
    @Qualifier("dataSource")
    private DataSource authDataSource;

    @Autowired
    @Qualifier("notificationDataSource")
    private DataSource notificationDataSource;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    @Qualifier("notificationJdbcClient")
    private JdbcClient notificationJdbcClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    @Qualifier("notificationTransactionManager")
    private PlatformTransactionManager notificationTransactionManager;

    @Autowired
    @Qualifier("notificationFlyway")
    private Flyway notificationFlyway;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void cleanState() {
        notificationJdbcClient.sql("DELETE FROM notification_idempotency_record").update();
        notificationJdbcClient.sql("DELETE FROM notification").update();
        notificationJdbcClient.sql("DELETE FROM consumer_dedup").update();
    }

    @Test
    void ownsIndependentPersistenceAndLoadsOnlyNotificationMigrationHistory() {
        assertThat(authDataSource).isNotSameAs(notificationDataSource);
        assertThat(jdbcClient).isNotSameAs(notificationJdbcClient);
        assertThat(transactionManager).isNotSameAs(notificationTransactionManager);
        assertThat(notificationDataSource).isInstanceOfSatisfying(HikariDataSource.class, pool -> {
            assertThat(pool.getPoolName()).isEqualTo("identity-notification");
            assertThat(pool.getMaximumPoolSize()).isEqualTo(2);
        });
        assertThat(notificationFlyway.getConfiguration().getLocations())
                .extracting(Object::toString)
                .containsExactly("classpath:db/identity-notification-test-migration");
        assertThat(count(notificationJdbcClient,
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true AND version IS NOT NULL"))
                .isOne();
        assertThat(context.containsBean("notificationEventConsumer")).isTrue();
        assertThat(context.containsBean("webSocketConfig")).isTrue();
        assertThat(getClass().getClassLoader()
                .getResource("db/notification-migration/V1__notification_schema.sql")).isNotNull();
        assertThat(getClass().getClassLoader()
                .getResource("db/migration/V1__notification_schema.sql")).isNull();

        assertThatThrownBy(() -> jdbcClient.sql("SELECT COUNT(*) FROM notification").query(Long.class).single())
                .isInstanceOf(BadSqlGrammarException.class);
        assertThatThrownBy(() -> notificationJdbcClient.sql("SELECT COUNT(*) FROM user_account")
                        .query(Long.class).single())
                .isInstanceOf(BadSqlGrammarException.class);
    }

    @Test
    void embeddedConsumerDeduplicatesAndApiUsesBearerSubject() throws Exception {
        String event = """
                {
                  "eventId":"cb8fa40d-b160-4d87-9af8-7c97b403a33b",
                  "type":"follow.changed",
                  "version":1,
                  "occurredAt":"2026-08-10T00:00:00Z",
                  "producer":"dc-user",
                  "aggregateId":"7",
                  "payload":{
                    "receiverId":7,
                    "actorId":2,
                    "actorUsername":"Orbit Reader",
                    "status":"FOLLOWED",
                    "targetType":"USER",
                    "targetId":2,
                    "targetRoute":"/users/2"
                  }
                }
                """;
        Object consumer = context.getBean("notificationEventConsumer");
        ReflectionTestUtils.invokeMethod(consumer, "consume", event);
        ReflectionTestUtils.invokeMethod(consumer, "consume", event);

        assertThat(count(notificationJdbcClient, "SELECT COUNT(*) FROM consumer_dedup")).isOne();
        assertThat(count(notificationJdbcClient, "SELECT COUNT(*) FROM notification")).isOne();
        assertThatThrownBy(() -> count(jdbcClient, "SELECT COUNT(*) FROM consumer_dedup"))
                .isInstanceOf(BadSqlGrammarException.class);

        String token = jwtService.issueAccess(7L, "Notification Reader", List.of("USER"),
                Instant.now().plusSeconds(60));
        mockMvc.perform(get("/api/notifications")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("X-User-Id", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].actorId").value(2))
                .andExpect(jsonPath("$.data[0].targetRoute").value("/users/2"));
        mockMvc.perform(get("/api/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(1));

        long notificationId = notificationJdbcClient.sql("SELECT id FROM notification")
                .query(Long.class).single();
        mockMvc.perform(post("/api/notifications/{id}/read", notificationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", "embedded-notification-read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.read").value(true))
                .andExpect(jsonPath("$.data.duplicateRequest").value(false));
        mockMvc.perform(post("/api/notifications/{id}/read", notificationId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", "embedded-notification-read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.duplicateRequest").value(true));
        assertThat(count(notificationJdbcClient, "SELECT COUNT(*) FROM notification_idempotency_record"))
                .isOne();
    }

    @Test
    void notificationApiRejectsForgedIdentityWithoutBearerToken() throws Exception {
        mockMvc.perform(get("/api/notifications").header("X-User-Id", "7"))
                .andExpect(status().isUnauthorized());
    }

    private long count(JdbcClient client, String sql) {
        return client.sql(sql).query(Long.class).single();
    }
}
