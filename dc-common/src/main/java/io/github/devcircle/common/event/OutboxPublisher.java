package io.github.devcircle.common.event;

public interface OutboxPublisher {
    int publishNewEvents(int batchSize);
}

