package io.github.devcircle.messaging.config;

import io.github.devcircle.messaging.outbox.ReliableOutboxWorker;
import io.github.devcircle.messaging.outbox.OutboxStatusService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Test
    void usesTheConfiguredLogicalServiceNameForEmbeddedDomains() {
        OutboxStatusService status = new ReliableMessagingConfig()
                .outboxStatusService(mock(JdbcClient.class), "dc-auth");

        assertThat(ReflectionTestUtils.getField(status, "serviceName")).isEqualTo("dc-auth");
    }

    @Test
    void productionRejectsMissingDefaultAndLowDiversityInternalOperationsKeys() {
        assertThatThrownBy(() -> ReliableMessagingConfig.validateInternalOperationsKey(true, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEVCIRCLE_INTERNAL_OPS_KEY");
        assertThatThrownBy(() -> ReliableMessagingConfig.validateInternalOperationsKey(
                true, "local-development-internal-ops-key"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ReliableMessagingConfig.validateInternalOperationsKey(
                true, "a".repeat(64)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void productionAcceptsAHighDiversityInternalOperationsKeyAndDevelopmentKeepsItsDefault() {
        ReliableMessagingConfig.validateInternalOperationsKey(
                true, "Ops-4w!N8@kR2#sV7~xM5qL9cD1pT6yH");
        ReliableMessagingConfig.validateInternalOperationsKey(
                false, "local-development-internal-ops-key");
    }
}
