package io.github.devcircle.user.application;

import io.github.devcircle.common.event.EventTopics;
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

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "devcircle.outbox.enabled=false",
        "devcircle.kafka.topics.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UserGraphServiceIntegrationTest {
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
        assertThat(repository.synchronizeProfile("evt-profile-1", EventTopics.USER_REGISTERED, 42L, "初始名称"))
                .isTrue();
        assertThat(repository.synchronizeProfile("evt-profile-2", EventTopics.USER_REGISTERED, 42L, "更新名称"))
                .isTrue();
        assertThat(repository.synchronizeProfile("evt-profile-2", EventTopics.USER_REGISTERED, 42L, "重复投递"))
                .isFalse();

        assertThat(repository.requireProfile(42L).username()).isEqualTo("更新名称");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM user_profile WHERE user_id = 42")
                .query(Long.class).single()).isEqualTo(1L);
    }
}
