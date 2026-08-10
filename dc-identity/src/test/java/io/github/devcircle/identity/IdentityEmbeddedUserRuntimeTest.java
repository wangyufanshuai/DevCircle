package io.github.devcircle.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.github.devcircle.auth.application.JwtService;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.common.event.ValidatedEvent;
import io.github.devcircle.messaging.outbox.OutboxStatusService;
import io.github.devcircle.messaging.outbox.ReliableOutboxWorker;
import io.github.devcircle.user.application.UserGraphService;
import io.github.devcircle.user.infrastructure.UserGraphRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "devcircle.identity.auth-datasource.url=jdbc:h2:mem:identity-user-auth;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "devcircle.identity.auth-datasource.username=sa",
        "devcircle.identity.auth-datasource.password=",
        "devcircle.identity.auth-datasource.maximum-pool-size=3",
        "devcircle.identity.auth-datasource.minimum-idle=0",
        "spring.flyway.enabled=true",
        "devcircle.user-runtime.datasource.url=jdbc:h2:mem:identity-user-domain;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "devcircle.user-runtime.datasource.username=sa",
        "devcircle.user-runtime.datasource.password=",
        "devcircle.user-runtime.datasource.maximum-pool-size=2",
        "devcircle.user-runtime.datasource.minimum-idle=0",
        "devcircle.user-runtime.flyway-enabled=true",
        "devcircle.user-runtime.flyway-locations=classpath:db/identity-user-test-migration",
        "devcircle.user.profile-backfill.enabled=false",
        "devcircle.outbox.enabled=true",
        "devcircle.outbox.initial-delay-ms=3600000",
        "spring.kafka.listener.auto-startup=false",
        "management.tracing.enabled=false"
})
@AutoConfigureMockMvc
@Import({IdentityEmbeddedAuthWriteTest.H2AuthMigrationConfig.class,
        IdentityEmbeddedUserRuntimeTest.OutboxKafkaConfig.class})
class IdentityEmbeddedUserRuntimeTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    @Qualifier("dataSource")
    private DataSource authDataSource;

    @Autowired
    @Qualifier("userDataSource")
    private DataSource userDataSource;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    @Qualifier("userJdbcClient")
    private JdbcClient userJdbcClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    @Qualifier("userTransactionManager")
    private PlatformTransactionManager userTransactionManager;

    @Autowired
    @Qualifier("userFlyway")
    private Flyway userFlyway;

    @Autowired
    private UserGraphService userGraphService;

    @Autowired
    private UserGraphRepository userGraphRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    @Qualifier("reliableOutboxWorker")
    private ReliableOutboxWorker authOutboxWorker;

    @Autowired
    @Qualifier("userReliableOutboxWorker")
    private ReliableOutboxWorker userOutboxWorker;

    @Autowired
    @Qualifier("outboxStatusService")
    private OutboxStatusService authOutboxStatusService;

    @Autowired
    private IdentityUserOutboxStatus userOutboxStatus;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void cleanUserState() {
        ensureOutboxTable();
        jdbcClient.sql("DELETE FROM outbox_event").update();
        userJdbcClient.sql("DELETE FROM outbox_event").update();
        userJdbcClient.sql("DELETE FROM consumer_dedup").update();
        userJdbcClient.sql("DELETE FROM user_idempotency").update();
        userJdbcClient.sql("DELETE FROM user_follow").update();
        userJdbcClient.sql("DELETE FROM user_profile").update();
        clearInvocations(kafkaTemplate);
    }

    @Test
    void ownsIndependentAuthAndUserPersistenceResourcesAndMigrations() {
        assertThat(authDataSource).isNotSameAs(userDataSource);
        assertThat(jdbcClient).isNotSameAs(userJdbcClient);
        assertThat(transactionManager).isNotSameAs(userTransactionManager);
        assertThat(authDataSource).isInstanceOfSatisfying(HikariDataSource.class, pool -> {
            assertThat(pool.getPoolName()).isEqualTo("identity-auth");
            assertThat(pool.getMaximumPoolSize()).isEqualTo(3);
        });
        assertThat(userDataSource).isInstanceOfSatisfying(HikariDataSource.class, pool -> {
            assertThat(pool.getPoolName()).isEqualTo("identity-user");
            assertThat(pool.getMaximumPoolSize()).isEqualTo(2);
        });
        assertThat(((HikariDataSource) authDataSource).getMaximumPoolSize()
                + ((HikariDataSource) userDataSource).getMaximumPoolSize()).isEqualTo(5);

        assertThat(count(jdbcClient,
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true AND version IS NOT NULL"))
                .isEqualTo(8);
        assertThat(count(userJdbcClient,
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true AND version IS NOT NULL"))
                .isOne();
        assertThat(userFlyway.getConfiguration().getLocations())
                .extracting(Object::toString)
                .containsExactly("classpath:db/identity-user-test-migration");

        assertThat(count(jdbcClient, "SELECT COUNT(*) FROM user_account")).isZero();
        assertThatThrownBy(() -> jdbcClient.sql("SELECT COUNT(*) FROM user_profile").query(Long.class).single())
                .isInstanceOf(BadSqlGrammarException.class);
        assertThatThrownBy(() -> userJdbcClient.sql("SELECT COUNT(*) FROM user_account").query(Long.class).single())
                .isInstanceOf(BadSqlGrammarException.class);
    }

    @Test
    void userProjectionAndFollowStayInUserDatabaseAndRemainIdempotent() throws Exception {
        assertThat(userGraphRepository.synchronizeProfile(registrationEvent(
                "11111111-1111-1111-1111-111111111111", 101L, "Embedded Alice"),
                101L, "Embedded Alice")).isTrue();
        ValidatedEvent bob = registrationEvent(
                "22222222-2222-2222-2222-222222222222", 102L, "Embedded Bob");
        assertThat(userGraphRepository.synchronizeProfile(bob, 102L, "Embedded Bob")).isTrue();
        assertThat(userGraphRepository.synchronizeProfile(bob, 102L, "Embedded Bob")).isFalse();

        var first = userGraphService.follow(101L, 102L, "identity-follow-101-102");
        var duplicate = userGraphService.follow(101L, 102L, "identity-follow-101-102");

        assertThat(first.duplicated()).isFalse();
        assertThat(duplicate.duplicated()).isTrue();
        assertThat(count(userJdbcClient, "SELECT COUNT(*) FROM user_profile")).isEqualTo(2);
        assertThat(count(userJdbcClient, "SELECT COUNT(*) FROM consumer_dedup")).isEqualTo(2);
        assertThat(count(userJdbcClient, "SELECT COUNT(*) FROM user_follow")).isOne();
        assertThat(count(userJdbcClient, "SELECT COUNT(*) FROM user_idempotency")).isOne();
        assertThat(count(userJdbcClient, "SELECT COUNT(*) FROM outbox_event WHERE topic = 'follow.changed'"))
                .isOne();
        assertThat(count(jdbcClient, "SELECT COUNT(*) FROM user_account")).isZero();

        mockMvc.perform(get("/api/users/101").header("X-Trace-Id", "identity-user-profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(101))
                .andExpect(jsonPath("$.data.username").value("Embedded Alice"))
                .andExpect(jsonPath("$.traceId").value("identity-user-profile"));
        mockMvc.perform(post("/api/users/102/follow")
                        .header("X-User-Id", "101")
                        .header("Idempotency-Key", "anonymous-follow-must-fail"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void onlyProfileAndBatchReadsAreAnonymous() throws Exception {
        synchronizeProfile("55555555-5555-5555-5555-555555555555", 301L, "Public Reader");

        mockMvc.perform(get("/api/users/301"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(301));
        mockMvc.perform(get("/api/users/batch?ids=301"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(301));

        for (String protectedPath : List.of(
                "/api/users/301/followees",
                "/api/users/301/followers",
                "/api/users/301/follow-state",
                "/api/users/301/recommendations",
                "/api/users/301/private")) {
            mockMvc.perform(get(protectedPath).header("X-Trace-Id", "protected-user-read"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
    }

    @Test
    void bearerSubjectOverridesAnUntrustedForwardedUserHeaderInsideIdentity() throws Exception {
        synchronizeProfile("66666666-6666-6666-6666-666666666666", 401L, "Token Owner");
        synchronizeProfile("77777777-7777-7777-7777-777777777777", 402L, "Follow Target");
        String token = jwtService.issueAccess(401L, "Token Owner", List.of("USER"),
                Instant.now().plusSeconds(60));

        mockMvc.perform(post("/api/users/402/follow")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("X-User-Id", "999")
                        .header("Idempotency-Key", "identity-trusted-subject"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.followed").value(true));

        assertThat(count(userJdbcClient,
                "SELECT COUNT(*) FROM user_follow WHERE follower_id = 401 AND followee_id = 402")).isOne();
        assertThat(count(userJdbcClient,
                "SELECT COUNT(*) FROM user_follow WHERE follower_id = 999")).isZero();
    }

    @Test
    void userWorkerPublishesOnlyTheUserDatabaseAndKeepsItsLogicalProducerIdentity() {
        synchronizeProfile("88888888-8888-8888-8888-888888888888", 501L, "Outbox Actor");
        synchronizeProfile("99999999-9999-9999-9999-999999999999", 502L, "Outbox Receiver");
        userGraphService.follow(501L, 502L, "identity-user-outbox");
        jdbcClient.sql("""
                INSERT INTO outbox_event(event_id, topic, aggregate_type, aggregate_id, payload)
                VALUES (:eventId, 'user.registered', 'USER', '601', CAST(:payload AS JSON))
                """)
                .param("eventId", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
                .param("payload", "{\"producer\":\"dc-auth\"}")
                .update();

        assertThat(authOutboxWorker).isNotSameAs(userOutboxWorker);
        assertThat(authOutboxStatusService.snapshot())
                .satisfies(status -> {
                    assertThat(status.service()).isEqualTo("dc-auth");
                    assertThat(status.backlog()).isOne();
                });
        assertThat(userOutboxStatus.snapshot())
                .satisfies(status -> {
                    assertThat(status.service()).isEqualTo("dc-user");
                    assertThat(status.backlog()).isOne();
                });
        assertThat(userJdbcClient.sql("""
                        SELECT payload FROM outbox_event WHERE topic = 'follow.changed'
                        """).query(String.class).single())
                .contains("dc-user");

        assertThat(userOutboxWorker.publishBatch(10)).isOne();

        assertThat(count(userJdbcClient,
                "SELECT COUNT(*) FROM outbox_event WHERE topic = 'follow.changed' AND status = 'PUBLISHED'"))
                .isOne();
        assertThat(count(jdbcClient,
                "SELECT COUNT(*) FROM outbox_event WHERE topic = 'user.registered' AND status = 'NEW'"))
                .isOne();
        assertThat(userOutboxStatus.snapshot().backlog()).isZero();
        assertThat(authOutboxStatusService.snapshot().backlog()).isOne();
        verify(kafkaTemplate).send(eq(EventTopics.FOLLOW_CHANGED), eq("502"), anyString());
    }

    @Test
    void identityOperationsEndpointReportsBothLogicalDomainsAndMakesUserFailureVisible() throws Exception {
        assertThat(authOutboxStatusService.snapshot().service()).isEqualTo("dc-auth");
        mockMvc.perform(get("/internal/ops/identity/outbox/status")
                        .header("X-Internal-Ops-Key", "local-development-internal-ops-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auth.service").value("dc-auth"))
                .andExpect(jsonPath("$.user.service").value("dc-user"));
        mockMvc.perform(get("/internal/ops/identity/outbox/auth/status")
                        .header("X-Internal-Ops-Key", "local-development-internal-ops-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("dc-auth"));
        mockMvc.perform(get("/internal/ops/identity/outbox/user/status")
                        .header("X-Internal-Ops-Key", "local-development-internal-ops-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("dc-user"));
        mockMvc.perform(get("/internal/ops/identity/outbox/user/status"))
                .andExpect(status().isForbidden());

        userJdbcClient.sql("DROP TABLE outbox_event").update();

        assertThat(authOutboxStatusService.snapshot())
                .satisfies(status -> {
                    assertThat(status.service()).isEqualTo("dc-auth");
                    assertThat(status.backlog()).isZero();
                });
        assertThatThrownBy(userOutboxStatus::snapshot)
                .isInstanceOf(BadSqlGrammarException.class);
        mockMvc.perform(get("/internal/ops/identity/outbox/status")
                        .header("X-Internal-Ops-Key", "local-development-internal-ops-key"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("SYSTEM_ERROR"));
    }

    @Test
    void userTransactionRollsBackClaimRelationshipAndCountersWhenOutboxWriteFails() {
        synchronizeProfile("33333333-3333-3333-3333-333333333333", 201L, "Rollback Alice");
        synchronizeProfile("44444444-4444-4444-4444-444444444444", 202L, "Rollback Bob");
        userJdbcClient.sql("DROP TABLE outbox_event").update();

        assertThatThrownBy(() -> userGraphService.follow(201L, 202L, "identity-follow-rollback"))
                .isInstanceOf(BadSqlGrammarException.class);

        assertThat(count(userJdbcClient, "SELECT COUNT(*) FROM user_follow")).isZero();
        assertThat(count(userJdbcClient, "SELECT COUNT(*) FROM user_idempotency")).isZero();
        assertThat(userGraphRepository.requireProfile(201L).followingCount()).isZero();
        assertThat(userGraphRepository.requireProfile(202L).followerCount()).isZero();
        assertThat(count(jdbcClient, "SELECT COUNT(*) FROM user_account")).isZero();
    }

    private void synchronizeProfile(String eventId, long userId, String displayName) {
        assertThat(userGraphRepository.synchronizeProfile(
                registrationEvent(eventId, userId, displayName), userId, displayName)).isTrue();
    }

    private ValidatedEvent registrationEvent(String eventId, long userId, String displayName) {
        return new ValidatedEvent(UUID.fromString(eventId), EventTopics.USER_REGISTERED, 1,
                Instant.parse("2026-08-10T00:00:00Z"), "dc-auth", Long.toString(userId),
                OBJECT_MAPPER.createObjectNode()
                        .put("userId", userId)
                        .put("displayName", displayName)
                        .put("status", "ACTIVE"));
    }

    private void ensureOutboxTable() {
        userJdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS outbox_event (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    event_id CHAR(36) NOT NULL UNIQUE,
                    topic VARCHAR(128) NOT NULL,
                    aggregate_type VARCHAR(64) NOT NULL,
                    aggregate_id VARCHAR(64) NOT NULL,
                    payload JSON NOT NULL,
                    status VARCHAR(32) NOT NULL DEFAULT 'NEW',
                    attempts INT NOT NULL DEFAULT 0,
                    next_attempt_at TIMESTAMP,
                    locked_at TIMESTAMP,
                    locked_by VARCHAR(64),
                    last_error VARCHAR(1000),
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    published_at TIMESTAMP
                )
                """).update();
    }

    private long count(JdbcClient client, String sql) {
        return client.sql(sql).query(Long.class).single();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class OutboxKafkaConfig {
        @Bean
        KafkaTemplate<String, String> kafkaTemplate() {
            @SuppressWarnings("unchecked")
            KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
            when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                    .thenReturn(CompletableFuture.completedFuture(null));
            return kafkaTemplate;
        }
    }
}
