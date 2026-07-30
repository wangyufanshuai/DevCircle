package io.github.devcircle.recommend.application;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
class RedisInteractionSignalStore implements InteractionSignalStore {
    private static final String SCRIPT = """
            if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
            redis.call('SET', KEYS[1], '1', 'EX', ARGV[5])
            redis.call('ZINCRBY', KEYS[2], ARGV[1], ARGV[2])
            redis.call('HINCRBY', KEYS[3], ARGV[3], ARGV[4])
            if tonumber(ARGV[4]) > 0 then
              redis.call('SADD', KEYS[4], ARGV[6])
              redis.call('EXPIRE', KEYS[4], ARGV[5])
            end
            return 1
            """;
    private static final DefaultRedisScript<Long> APPLY_SCRIPT = new DefaultRedisScript<>(SCRIPT, Long.class);

    private final StringRedisTemplate redisTemplate;

    RedisInteractionSignalStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void apply(String eventId, long actorId, long postId, String interactionType) {
        String normalized = interactionType == null ? "" : interactionType.toUpperCase();
        double weight = switch (normalized) {
            case "LIKE" -> 3.0;
            case "UNLIKE" -> -3.0;
            case "FAVORITE" -> 5.0;
            case "UNFAVORITE" -> -5.0;
            default -> throw new IllegalArgumentException("Unsupported interaction type");
        };
        long direction = weight > 0 ? 1 : -1;
        String field = normalized.replace("UN", "").toLowerCase();
        Long applied = redisTemplate.execute(APPLY_SCRIPT,
                List.of("recommend:interaction:event:" + eventId,
                        "feed:hot-posts", "post:" + postId + ":stats", "user:" + actorId + ":interest-tags"),
                Double.toString(weight), field, Long.toString(direction), Long.toString(direction),
                "604800", field);
        if (applied == null) throw new IllegalStateException("Redis interaction signal update returned no result");
    }
}
