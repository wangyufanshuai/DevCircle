package io.github.devcircle.messaging.config;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KafkaConsumerReliabilityConfigTest {
    @Test
    void wrappedLeaseContentionNeverExhaustsIntoDltButOtherFailuresKeepDefaultPolicy() {
        var backOff = KafkaConsumerReliabilityConfig.leaseBackOff(
                new IllegalStateException("listener wrapper", new ConsumerLeaseBusyException("owned")), 17);
        var execution = backOff.start();
        for (int attempt = 0; attempt < 1000; attempt++) {
            assertThat(execution.nextBackOff()).isEqualTo(17L);
        }
        assertThat(KafkaConsumerReliabilityConfig.leaseBackOff(new IllegalArgumentException("invalid event"), 17)).isNull();
    }

    @Test
    void invalidRetryIntervalsAreClampedToAValidBackoff() {
        @SuppressWarnings("unchecked")
        ObjectProvider<KafkaTemplate<?, ?>> provider = mock(ObjectProvider.class);

        assertThatCode(() -> new KafkaConsumerReliabilityConfig()
                .kafkaConsumerErrorHandler(provider, -100, 0.0, -200, 0, -1))
                .doesNotThrowAnyException();
    }

    @Test
    void dltSendFailureIsSurfacedInsteadOfAcknowledgingTheSourceRecord() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("dlt broker down")));

        DeadLetterPublishingRecoverer recoverer = new KafkaConsumerReliabilityConfig()
                .deadLetterRecoverer(kafka, 100);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("source.topic", 0, 4L, "key", "payload");

        assertThatThrownBy(() -> recoverer.accept(record, new IllegalStateException("poison event")))
                .isInstanceOf(Exception.class);
    }

    @Test
    void configuredHandlerUsesTheSharedDefaultErrorHandler() {
        @SuppressWarnings("unchecked")
        ObjectProvider<KafkaTemplate<?, ?>> provider = mock(ObjectProvider.class);

        DefaultErrorHandler handler = new KafkaConsumerReliabilityConfig()
                .kafkaConsumerErrorHandler(provider, 100, 2.0, 1_000, 2, 1_000);

        assertThat(handler).isNotNull();
        assertThat(handler.isAckAfterHandle()).isTrue();
    }
}
