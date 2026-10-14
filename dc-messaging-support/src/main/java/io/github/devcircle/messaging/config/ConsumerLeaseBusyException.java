package io.github.devcircle.messaging.config;

/** A valid record is owned by another live lease; it must not be acknowledged or sent to DLT. */
public class ConsumerLeaseBusyException extends IllegalStateException {
    public ConsumerLeaseBusyException(String message) {
        super(message);
    }
}
