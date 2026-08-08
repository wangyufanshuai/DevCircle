package io.github.devcircle.common.config;

import io.github.devcircle.common.event.ReliableOutboxWorker;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ReliableMessagingConfigTest {
    @Test
    void appliesConfiguredBatchRetryAndSendTimeoutLimits() {
        ReliableOutboxWorker worker = new ReliableMessagingConfig().reliableOutboxWorker(
                mock(JdbcClient.class), mock(KafkaTemplate.class), 17, 3, 750);

        assertThat(ReflectionTestUtils.getField(worker, "batchSize")).isEqualTo(17);
        assertThat(ReflectionTestUtils.getField(worker, "maxAttempts")).isEqualTo(3);
        assertThat(ReflectionTestUtils.getField(worker, "sendTimeoutMs")).isEqualTo(750L);
    }
}
