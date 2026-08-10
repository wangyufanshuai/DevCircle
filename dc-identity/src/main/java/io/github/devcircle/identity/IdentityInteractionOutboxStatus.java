package io.github.devcircle.identity;

import io.github.devcircle.messaging.outbox.OutboxStatusService;
import io.github.devcircle.messaging.outbox.OutboxStatusView;

/** Distinguishes the embedded Interaction logical domain from Auth and User outboxes. */
final class IdentityInteractionOutboxStatus {
    private final OutboxStatusService delegate;

    IdentityInteractionOutboxStatus(OutboxStatusService delegate) {
        this.delegate = delegate;
    }

    OutboxStatusView snapshot() {
        return delegate.snapshot();
    }
}
