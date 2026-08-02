package io.github.devcircle.user.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.event.EventTopics;
import io.github.devcircle.common.event.ValidatedEvent;
import io.github.devcircle.user.infrastructure.UserGraphRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "devcircle.outbox.enabled=false",
        "devcircle.kafka.topics.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UserGraphServiceIntegrationTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("dc_user")
            .withUsername("devcircle")
            .withPassword("devcircle");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    UserGraphService service;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    UserGraphRepository repository;

    @BeforeEach
    void cleanState() {
        jdbcClient.sql("DELETE FROM outbox_event").update();
        jdbcClient.sql("DELETE FROM consumer_dedup").update();
        jdbcClient.sql("DELETE FROM user_idempotency").update();
        jdbcClient.sql("DELETE FROM user_follow").update();
        jdbcClient.sql("DELETE FROM user_profile").update();
        for (long userId : new long[]{1, 2, 3, 10, 11}) {
            jdbcClient.sql("INSERT INTO user_profile(user_id, username) VALUES (:userId, :username)")
                    .param("userId", userId).param("username", "user-" + userId).update();
        }
    }

    @Test
    void followIsIdempotentAndPublishesOnlyRealStateChanges() {
        var first = service.follow(1L, 2L, "follow-1-2");
        var duplicate = service.follow(1L, 2L, "follow-1-2");

        assertThat(first.duplicated()).isFalse();
        assertThat(duplicate.duplicated()).isTrue();
        assertThat(service.followees(1L)).containsExactly(2L);
        assertThat(service.followers(2L)).containsExactly(1L);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM outbox_event WHERE topic = 'follow.changed'")
                .query(Long.class).single()).isEqualTo(1L);

        service.unfollow(1L, 2L, "unfollow-1-2");
        assertThat(service.followees(1L)).isEmpty();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM outbox_event WHERE topic = 'follow.changed'")
                .query(Long.class).single()).isEqualTo(2L);
    }

    @Test
    void twoHopRecommendationsUseMutualFollowees() {
        service.follow(1L, 10L, "f-1-10");
        service.follow(2L, 10L, "f-2-10");
        service.follow(3L, 10L, "f-3-10");
        service.follow(3L, 11L, "f-3-11");
        service.follow(1L, 11L, "f-1-11");

        var recommendations = service.recommendations(1L);

        assertThat(recommendations).extracting(item -> item.userId()).contains(3L, 2L);
        assertThat(recommendations.getFirst().mutualFollowees()).isGreaterThanOrEqualTo(1L);
        assertThat(recommendations.getFirst().reason()).contains("共同关注");
    }

    @Test
    void profileProjectionUpsertsOnMySqlWithoutDuplicatingTheUser() {
        ValidatedEvent initial = registrationEvent(
                "5bb06ed9-693e-4a42-8219-52903156814e", 42L, "初始名称");
        ValidatedEvent updated = registrationEvent(
                "82982762-df78-41fb-ac48-a95826e5bc95", 42L, "更新名称");
        assertThat(repository.synchronizeProfile(initial, 42L, "初始名称"))
                .isTrue();
        assertThat(repository.synchronizeProfile(updated, 42L, "更新名称"))
                .isTrue();
        assertThat(repository.synchronizeProfile(updated, 42L, "更新名称"))
                .isFalse();
        assertThatThrownBy(() -> repository.synchronizeProfile(
                registrationEvent(updated.eventId().toString(), 42L, "变形重放"), 42L, "变形重放"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Event ID is already bound to a different payload");

        assertThat(repository.requireProfile(42L).username()).isEqualTo("更新名称");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM user_profile WHERE user_id = 42")
                .query(Long.class).single()).isEqualTo(1L);
    }

    private ValidatedEvent registrationEvent(String eventId, long userId, String displayName) {
        return new ValidatedEvent(UUID.fromString(eventId), EventTopics.USER_REGISTERED, 1,
                Instant.parse("2026-08-01T10:15:30Z"), "dc-auth", Long.toString(userId),
                OBJECT_MAPPER.createObjectNode()
                        .put("userId", userId)
                        .put("displayName", displayName)
                        .put("status", "ACTIVE"));
    }
}
