package io.github.devcircle.gateway.filter;

import reactor.core.publisher.Mono;

import java.time.Duration;

/** Shared counter abstraction used by every Gateway replica. */
interface RateLimitStore {
    Mono<Integer> increment(String key, Duration expiry);
}
