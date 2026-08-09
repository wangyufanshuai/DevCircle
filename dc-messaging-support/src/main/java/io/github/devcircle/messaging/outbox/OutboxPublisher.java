package io.github.devcircle.messaging.outbox;

public interface OutboxPublisher {
    int publishNewEvents(int batchSize);
}
