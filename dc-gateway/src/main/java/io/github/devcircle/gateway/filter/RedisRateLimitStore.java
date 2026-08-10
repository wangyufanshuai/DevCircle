package io.github.devcircle.gateway.filter;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/** Redis-backed atomic fixed-window counter shared by all Gateway replicas. */
@Component
final class RedisRateLimitStore implements RateLimitStore {
    private static final RedisScript<Long> INCREMENT_AND_EXPIRE = RedisScript.of("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);

    private final ReactiveStringRedisTemplate redis;

    RedisRateLimitStore(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Mono<Integer> increment(String key, Duration expiry) {
        long seconds = Math.max(1, expiry.toSeconds());
        return redis.execute(INCREMENT_AND_EXPIRE, List.of(key), List.of(Long.toString(seconds)))
                .single()
                .map(Math::toIntExact);
    }
}
