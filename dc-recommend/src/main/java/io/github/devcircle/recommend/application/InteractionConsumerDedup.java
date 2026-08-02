package io.github.devcircle.recommend.application;

import io.github.devcircle.common.event.ValidatedEvent;

interface InteractionConsumerDedup {
    ClaimResult claim(ValidatedEvent event);

    void markCompleted(String eventId, String leaseToken);

    void markFailed(String eventId, String leaseToken, String message);

    record ClaimResult(boolean claimed, String leaseToken) {
        static ClaimResult acquired(String leaseToken) {
            return new ClaimResult(true, leaseToken);
        }

        static ClaimResult duplicate() {
            return new ClaimResult(false, null);
        }
    }
}
