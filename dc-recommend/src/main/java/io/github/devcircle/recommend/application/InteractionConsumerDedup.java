package io.github.devcircle.recommend.application;

import io.github.devcircle.common.event.ValidatedEvent;

interface InteractionConsumerDedup {
    boolean claim(ValidatedEvent event);

    void markCompleted(String eventId);

    void markFailed(String eventId, String message);
}
