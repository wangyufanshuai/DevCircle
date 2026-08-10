package io.github.devcircle.identity;

import com.zaxxer.hikari.HikariDataSource;
import io.github.devcircle.auth.application.JwtService;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.interaction.application.PostPublicationGateway;
import io.github.devcircle.interaction.application.InteractionService;
import io.github.devcircle.messaging.outbox.OutboxStatusService;
import io.github.devcircle.messaging.outbox.ReliableOutboxWorker;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "devcircle.identity.auth-datasource.url=jdbc:h2:mem:identity-interaction-auth;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "devcircle.identity.auth-datasource.username=sa",
        "devcircle.identity.auth-datasource.password=",
        "devcircle.identity.auth-datasource.maximum-pool-size=3",
        "devcircle.identity.auth-datasource.minimum-idle=0",
        "spring.flyway.enabled=true",
        "devcircle.user-runtime.datasource.url=jdbc:h2:mem:identity-interaction-user;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "devcircle.user-runtime.datasource.username=sa",
        "devcircle.user-runtime.datasource.password=",
        "devcircle.user-runtime.datasource.maximum-pool-size=2",
        "devcircle.user-runtime.datasource.minimum-idle=0",
        "devcircle.user-runtime.flyway-enabled=true",
        "devcircle.user-runtime.flyway-locations=classpath:db/identity-user-test-migration",
        "devcircle.interaction-runtime.datasource.url=jdbc:h2:mem:identity-interaction-domain;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "devcircle.interaction-runtime.datasource.username=sa",
        "devcircle.interaction-runtime.datasource.password=",
        "devcircle.interaction-runtime.datasource.maximum-pool-size=2",
        "devcircle.interaction-runtime.datasource.minimum-idle=0",
        "devcircle.interaction-runtime.flyway-enabled=true",
        "devcircle.interaction-runtime.flyway-locations=classpath:db/identity-interaction-test-migration",
        "devcircle.notification-runtime.flyway-enabled=false",
        "devcircle.user.profile-backfill.enabled=false",
        "devcircle.outbox.enabled=true",
        "devcircle.outbox.initial-delay-ms=3600000",
        "devcircle.kafka.topics.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "management.tracing.enabled=false"
})
@AutoConfigureMockMvc
@Import({IdentityEmbeddedAuthWriteTest.H2AuthMigrationConfig.class,
        IdentityEmbeddedInteractionRuntimeTest.InteractionTestConfig.class})
class IdentityEmbeddedInteractionRuntimeTest {
    @Autowired
    @Qualifier("dataSource")
    private DataSource authDataSource;

    @Autowired
    @Qualifier("userDataSource")
    private DataSource userDataSource;

    @Autowired
    @Qualifier("interactionDataSource")
    private DataSource interactionDataSource;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    @Qualifier("userJdbcClient")
    private JdbcClient userJdbcClient;

    @Autowired
    @Qualifier("interactionJdbcClient")
    private JdbcClient interactionJdbcClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    @Qualifier("userTransactionManager")
    private PlatformTransactionManager userTransactionManager;

    @Autowired
    @Qualifier("interactionTransactionManager")
    private PlatformTransactionManager interactionTransactionManager;

    @Autowired
    @Qualifier("interactionFlyway")
    private Flyway interactionFlyway;

    @Autowired
    private InteractionService interactionService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    @Qualifier("interactionReliableOutboxWorker")
    private ReliableOutboxWorker interactionOutboxWorker;

    @Autowired
    @Qualifier("outboxStatusService")
    private OutboxStatusService authOutboxStatusService;

    @Autowired
    private IdentityUserOutboxStatus userOutboxStatus;

    @Autowired
    private IdentityInteractionOutboxStatus interactionOutboxStatus;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void cleanState() {
        ensureInteractionOutboxTable();
        jdbcClient.sql("DELETE FROM outbox_event").update();
        userJdbcClient.sql("DELETE FROM outbox_event").update();
        interactionJdbcClient.sql("DELETE FROM outbox_event").update();
        interactionJdbcClient.sql("DELETE FROM idempotency_record").update();
        interactionJdbcClient.sql("DELETE FROM like_record").update();
        interactionJdbcClient.sql("DELETE FROM favorite_record").update();
        interactionJdbcClient.sql("DELETE FROM post_counter").update();
        clearInvocations(kafkaTemplate);
    }

    @Test
    void ownsThreeIndependentPersistenceResourcesAndInteractionMigrationHistory() {
        assertThat(authDataSource).isNotSameAs(userDataSource).isNotSameAs(interactionDataSource);
        assertThat(userDataSource).isNotSameAs(interactionDataSource);
        assertThat(jdbcClient).isNotSameAs(userJdbcClient).isNotSameAs(interactionJdbcClient);
        assertThat(userJdbcClient).isNotSameAs(interactionJdbcClient);
        assertThat(transactionManager).isNotSameAs(userTransactionManager)
                .isNotSameAs(interactionTransactionManager);
        assertThat(userTransactionManager).isNotSameAs(interactionTransactionManager);

        assertPool(authDataSource, "identity-auth", 3);
        assertPool(userDataSource, "identity-user", 2);
        assertPool(interactionDataSource, "identity-interaction", 2);
        assertThat(poolSize(authDataSource) + poolSize(userDataSource) + poolSize(interactionDataSource))
                .isEqualTo(7);
        assertThat(interactionFlyway.getConfiguration().getLocations())
                .extracting(Object::toString)
                .containsExactly("classpath:db/identity-interaction-test-migration");
        assertThat(count(interactionJdbcClient,
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true AND version IS NOT NULL"))
                .isOne();

        assertThatThrownBy(() -> jdbcClient.sql("SELECT COUNT(*) FROM like_record").query(Long.class).single())
                .isInstanceOf(BadSqlGrammarException.class);
        assertThatThrownBy(() -> userJdbcClient.sql("SELECT COUNT(*) FROM like_record").query(Long.class).single())
                .isInstanceOf(BadSqlGrammarException.class);
        assertThatThrownBy(() -> interactionJdbcClient.sql("SELECT COUNT(*) FROM user_account")
                        .query(Long.class).single())
                .isInstanceOf(BadSqlGrammarException.class);
        assertThatThrownBy(() -> interactionJdbcClient.sql("SELECT COUNT(*) FROM user_profile")
                        .query(Long.class).single())
                .isInstanceOf(BadSqlGrammarException.class);
    }

    @Test
    void embeddedApiUsesBearerSubjectAndKeepsLikeFavoriteTogglesIdempotent() throws Exception {
        String token = jwtService.issueAccess(7L, "Interaction Reader", List.of("USER"),
                Instant.now().plusSeconds(60));

        performWrite(post("/api/posts/9001/like"), token, "like-9001")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.duplicated").value(false));
        performWrite(post("/api/posts/9001/like"), token, "like-9001")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.duplicated").value(true));
        performWrite(post("/api/posts/9001/favorite"), token, "favorite-9001")
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/posts/9001/interaction-state")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("X-User-Id", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(true))
                .andExpect(jsonPath("$.data.favorited").value(true))
                .andExpect(jsonPath("$.data.likeCount").value(11))
                .andExpect(jsonPath("$.data.favoriteCount").value(1));
        mockMvc.perform(get("/api/me/favorites")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("X-User-Id", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value(9001));

        performWrite(delete("/api/posts/9001/like"), token, "unlike-9001")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false));
        performWrite(delete("/api/posts/9001/favorite"), token, "unfavorite-9001")
                .andExpect(status().isOk());

        assertThat(count(interactionJdbcClient,
                "SELECT COUNT(*) FROM like_record WHERE user_id = 999")).isZero();
        assertThat(count(interactionJdbcClient,
                "SELECT COUNT(*) FROM idempotency_record")).isEqualTo(4);
        assertThat(count(interactionJdbcClient,
                "SELECT COUNT(*) FROM outbox_event WHERE topic = 'interaction.changed'"))
                .isEqualTo(4);
        assertThat(count(jdbcClient, "SELECT COUNT(*) FROM outbox_event")).isZero();
        assertThat(count(userJdbcClient, "SELECT COUNT(*) FROM outbox_event")).isZero();
    }

    @Test
    void anonymousOrUnexpectedInteractionRoutesAreNotExposed() throws Exception {
        mockMvc.perform(get("/api/posts/9001/interaction-state")
                        .header("X-User-Id", "7"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/posts/9001/interaction-debug"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void interactionTransactionRollsBackClaimStateCounterWhenOutboxWriteFails() {
        interactionJdbcClient.sql("DROP TABLE outbox_event").update();

        assertThatThrownBy(() -> interactionService.like(7L, 9002L, "rollback-like"))
                .isInstanceOf(BadSqlGrammarException.class);

        assertThat(count(interactionJdbcClient, "SELECT COUNT(*) FROM like_record")).isZero();
        assertThat(count(interactionJdbcClient, "SELECT COUNT(*) FROM post_counter")).isZero();
        assertThat(count(interactionJdbcClient, "SELECT COUNT(*) FROM idempotency_record")).isZero();
        assertThat(count(jdbcClient, "SELECT COUNT(*) FROM outbox_event")).isZero();
        assertThat(count(userJdbcClient, "SELECT COUNT(*) FROM outbox_event")).isZero();
    }

    @Test
    void interactionWorkerPublishesOnlyInteractionDatabaseAndStatusReportsAllDomains() throws Exception {
        interactionService.like(7L, 9003L, "worker-like");
        insertSentinelOutbox(jdbcClient, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "user.registered", "dc-auth");
        insertSentinelOutbox(userJdbcClient, "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", "follow.changed", "dc-user");

        assertThat(authOutboxStatusService.snapshot().backlog()).isOne();
        assertThat(userOutboxStatus.snapshot().backlog()).isOne();
        assertThat(interactionOutboxStatus.snapshot())
                .satisfies(snapshot -> {
                    assertThat(snapshot.service()).isEqualTo("dc-interaction");
                    assertThat(snapshot.backlog()).isOne();
                });

        assertThat(interactionOutboxWorker.publishBatch(10)).isOne();

        assertThat(count(interactionJdbcClient,
                "SELECT COUNT(*) FROM outbox_event WHERE status = 'PUBLISHED'")).isOne();
        assertThat(count(jdbcClient,
                "SELECT COUNT(*) FROM outbox_event WHERE status = 'NEW'")).isOne();
        assertThat(count(userJdbcClient,
                "SELECT COUNT(*) FROM outbox_event WHERE status = 'NEW'")).isOne();
        verify(kafkaTemplate).send(eq(EventTopics.INTERACTION_CHANGED), eq("9003"), anyString());

        mockMvc.perform(get("/internal/ops/identity/outbox/status")
                        .header("X-Internal-Ops-Key", "local-development-internal-ops-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auth.service").value("dc-auth"))
                .andExpect(jsonPath("$.user.service").value("dc-user"))
                .andExpect(jsonPath("$.interaction.service").value("dc-interaction"));
        mockMvc.perform(get("/internal/ops/identity/outbox/interaction/status")
                        .header("X-Internal-Ops-Key", "local-development-internal-ops-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("dc-interaction"));
    }

    private org.springframework.test.web.servlet.ResultActions performWrite(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String token, String key) throws Exception {
        return mockMvc.perform(request
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-User-Id", "999")
                .header("Idempotency-Key", key));
    }

    private void insertSentinelOutbox(
            JdbcClient client, String eventId, String topic, String producer) {
        client.sql("""
                INSERT INTO outbox_event(event_id, topic, aggregate_type, aggregate_id, payload)
                VALUES (:eventId, :topic, 'TEST', '1', CAST(:payload AS JSON))
                """)
                .param("eventId", eventId)
                .param("topic", topic)
                .param("payload", "{\"producer\":\"" + producer + "\"}")
                .update();
    }

    private void ensureInteractionOutboxTable() {
        interactionJdbcClient.sql("""
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

    private void assertPool(DataSource dataSource, String name, int maximumPoolSize) {
        assertThat(dataSource).isInstanceOfSatisfying(HikariDataSource.class, pool -> {
            assertThat(pool.getPoolName()).isEqualTo(name);
            assertThat(pool.getMaximumPoolSize()).isEqualTo(maximumPoolSize);
        });
    }

    private int poolSize(DataSource dataSource) {
        return ((HikariDataSource) dataSource).getMaximumPoolSize();
    }

    private long count(JdbcClient client, String sql) {
        return client.sql(sql).query(Long.class).single();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class InteractionTestConfig {
        @Bean
        @Primary
        PostPublicationGateway postPublicationGateway() {
            return postId -> new PostPublicationGateway.PublishedPost(postId, 10, 42L);
        }

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
