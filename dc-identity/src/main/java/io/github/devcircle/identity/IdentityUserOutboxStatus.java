package io.github.devcircle.identity;

import io.github.devcircle.messaging.outbox.OutboxStatusService;
import io.github.devcircle.messaging.outbox.OutboxStatusView;

/** Distinguishes the embedded User logical domain from Identity's primary Auth outbox bean. */
final class IdentityUserOutboxStatus {
    private final OutboxStatusService delegate;

    IdentityUserOutboxStatus(OutboxStatusService delegate) {
        this.delegate = delegate;
    }

    OutboxStatusView snapshot() {
        return delegate.snapshot();
    }
}
