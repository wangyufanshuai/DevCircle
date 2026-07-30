package io.github.devcircle.recommend.application;

interface InteractionConsumerDedup {
    boolean claim(String eventId, String topic);

    void markCompleted(String eventId);

    void markFailed(String eventId, String message);
}
