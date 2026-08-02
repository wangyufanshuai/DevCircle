package io.github.devcircle.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.content.api.CreatePostRequest;
import io.github.devcircle.content.infrastructure.JdbcOutboxPublisher;
import io.github.devcircle.content.infrastructure.PostRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=127.0.0.1:1",
        "spring.kafka.listener.auto-startup=false",
        "devcircle.kafka.topics.enabled=false",
        "devcircle.outbox.initial-delay-ms=3600000",
        "spring.kafka.producer.properties.max.block.ms=1000",
        "spring.kafka.producer.properties.request.timeout.ms=1000",
        "spring.kafka.producer.properties.delivery.timeout.ms=2000"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxFailureIntegrationTest {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("dc_content")
            .withUsername("devcircle")
            .withPassword("devcircle");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    PostRepository postRepository;

    @Autowired
    JdbcOutboxPublisher outboxPublisher;

    @Autowired
    JdbcClient jdbcClient;

    @TestConfiguration
    static class TestBeans {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }

    @Test
    void failedKafkaPublishMarksOutboxEventFailedForRetry() {
        var created = postRepository.create(51L, new CreatePostRequest(
                "Kafka 不可用时保留 Outbox",
                "发送失败不能把事件标记为已发布，后续补偿任务需要继续重放。",
                List.of("Kafka", "Outbox")
        ));

        assertThatThrownBy(() -> outboxPublisher.publishNewEvents(10))
                .isInstanceOf(IllegalStateException.class);

        String status = jdbcClient.sql("SELECT status FROM outbox_event WHERE aggregate_id = :postId")
                .param("postId", created.id().toString())
                .query(String.class)
                .single();
        assertThat(status).isEqualTo("FAILED");
    }
}
