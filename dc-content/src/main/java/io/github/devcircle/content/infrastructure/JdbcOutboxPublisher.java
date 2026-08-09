package io.github.devcircle.content.infrastructure;

import io.github.devcircle.messaging.outbox.OutboxPublisher;
import io.github.devcircle.messaging.outbox.ReliableOutboxWorker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "devcircle.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JdbcOutboxPublisher implements OutboxPublisher {
    private final ReliableOutboxWorker worker;

    public JdbcOutboxPublisher(ReliableOutboxWorker worker) {
        this.worker = worker;
    }

    @Override
    public int publishNewEvents(int batchSize) {
        return worker.publishBatch(batchSize);
    }
}
