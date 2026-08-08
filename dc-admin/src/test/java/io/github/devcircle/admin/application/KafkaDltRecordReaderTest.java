package io.github.devcircle.admin.application;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaDltRecordReaderTest {
    @Test
    void consumerTimeoutsAreBoundedAndInternallyValid() {
        var properties = new KafkaDltRecordReader("127.0.0.1:1", 5000).consumerProperties();

        long apiTimeout = Long.parseLong(String.valueOf(
                properties.get(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG)));
        long requestTimeout = Long.parseLong(String.valueOf(
                properties.get(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG)));
        long sessionTimeout = Long.parseLong(String.valueOf(
                properties.get(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG)));

        assertThat(apiTimeout).isEqualTo(5000);
        assertThat(requestTimeout).isGreaterThan(sessionTimeout);
        assertThat(sessionTimeout).isPositive();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.close(Duration.ZERO);
        }
    }
}
