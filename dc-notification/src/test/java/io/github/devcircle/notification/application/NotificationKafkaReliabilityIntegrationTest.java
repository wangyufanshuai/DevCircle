package io.github.devcircle.notification.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.event.EventEnvelope;
import io.github.devcircle.common.event.EventTopics;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.lifecycle.timeout-per-shutdown-phase=2s",
        "spring.kafka.admin.fail-fast=true",
        "devcircle.kafka.retry.initial-interval-ms=50",
        "devcircle.kafka.retry.multiplier=2.0",
        "devcircle.kafka.retry.max-interval-ms=200",
        "devcircle.kafka.retry.max-attempts=3"
})
class NotificationKafkaReliabilityIntegrationTest {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("dc_notification")
            .withUsername("devcircle")
            .withPassword("devcircle");

    @Container
    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.1"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    KafkaTemplate<Object, Object> kafkaTemplate;

    @Autowired
    KafkaListenerEndpointRegistry listenerRegistry;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void waitForListenerAndCleanDatabase() {
        listenerRegistry.getListenerContainers()
                .forEach(container -> ContainerTestUtils.waitForAssignment(container, 4));
        jdbcClient.sql("DELETE FROM notification").update();
        jdbcClient.sql("DELETE FROM consumer_dedup").update();
    }

    @Test
    void poisonEventIsRetriedThenPublishedToDltAndValidDuplicatesStayIdempotent() throws Exception {
        String poison = "{\"eventId\":";
        ConsumerRecord<String, String> deadLetter;
        try (KafkaConsumer<String, String> consumer = deadLetterConsumer()) {
            consumer.subscribe(List.of(EventTopics.AUDIT_COMPLETED + ".DLT"));
            consumer.poll(Duration.ofMillis(100));
            kafkaTemplate.send(EventTopics.AUDIT_COMPLETED, "poison", poison).get(10, TimeUnit.SECONDS);
            deadLetter = awaitRecord(consumer, Duration.ofSeconds(15));
        }

        assertThat(deadLetter.value()).isEqualTo(poison);
        Header originalTopic = deadLetter.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC);
        assertThat(originalTopic).isNotNull();
        assertThat(new String(originalTopic.value(), StandardCharsets.UTF_8)).isEqualTo(EventTopics.AUDIT_COMPLETED);
        assertThat(count("notification")).isZero();
        assertThat(count("consumer_dedup")).isZero();

        EventEnvelope valid = EventEnvelope.now(EventTopics.AUDIT_COMPLETED, "dc-content", "9001",
                Map.of("receiverId", 42L, "postId", 9001L, "status", "PUBLISHED"));
        String message = objectMapper.writeValueAsString(valid);
        kafkaTemplate.send(EventTopics.AUDIT_COMPLETED, valid.eventId().toString(), message).get(10, TimeUnit.SECONDS);
        kafkaTemplate.send(EventTopics.AUDIT_COMPLETED, valid.eventId().toString(), message).get(10, TimeUnit.SECONDS);

        awaitCount("notification", 1L, Duration.ofSeconds(10));
        assertThat(count("consumer_dedup")).isEqualTo(1L);
    }

    private KafkaConsumer<String, String> deadLetterConsumer() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "notification-dlt-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(properties);
    }

    private ConsumerRecord<String, String> awaitRecord(KafkaConsumer<String, String> consumer, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(250))) {
                return record;
            }
        }
        return fail("No dead-letter record received within " + timeout);
    }

    private void awaitCount(String table, long expected, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (count(table) == expected) {
                return;
            }
            Thread.sleep(100);
        }
        fail("Expected " + table + " count to become " + expected + " but was " + count(table));
    }

    private long count(String table) {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}
