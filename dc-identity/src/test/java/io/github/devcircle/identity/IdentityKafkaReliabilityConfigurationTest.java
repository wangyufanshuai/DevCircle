package io.github.devcircle.identity;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.listener.ContainerProperties;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "devcircle.identity.auth-datasource.url=jdbc:h2:mem:identity-kafka-contract;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "devcircle.identity.auth-datasource.username=sa",
        "devcircle.identity.auth-datasource.password=",
        "spring.flyway.enabled=false",
        "devcircle.user-runtime.flyway-enabled=false",
        "devcircle.user.profile-backfill.enabled=false",
        "devcircle.outbox.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "management.tracing.enabled=false"
})
class IdentityKafkaReliabilityConfigurationTest {
    @Autowired
    private KafkaProperties kafka;

    @Test
    void preservesStandaloneUserConsumerAndProducerReliabilityBaseline() {
        assertThat(kafka.getConsumer().getEnableAutoCommit()).isFalse();
        assertThat(kafka.getConsumer().getAutoOffsetReset()).isEqualTo("earliest");
        assertThat(kafka.getProducer().getAcks()).isEqualTo("all");
        assertThat(kafka.getProducer().getProperties()).containsEntry("enable.idempotence", "true");
        assertThat(kafka.getListener().getAckMode()).isEqualTo(ContainerProperties.AckMode.RECORD);

        assertThat(kafka.buildConsumerProperties())
                .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        assertThat(kafka.buildProducerProperties())
                .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
    }
}
