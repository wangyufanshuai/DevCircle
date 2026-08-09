package io.github.devcircle.content.infrastructure;

import io.github.devcircle.messaging.outbox.ReliableOutboxWorker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcOutboxPublisherTest {
    @Test
    void manualPublishDelegatesToTheReliableLeaseWorker() {
        ReliableOutboxWorker worker = mock(ReliableOutboxWorker.class);
        when(worker.publishBatch(25)).thenReturn(4);
        JdbcOutboxPublisher publisher = new JdbcOutboxPublisher(worker);

        assertThat(publisher.publishNewEvents(25)).isEqualTo(4);
        verify(worker).publishBatch(25);
    }
}
