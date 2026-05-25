package io.github.devcircle.interaction.application;

import io.github.devcircle.interaction.api.InteractionResult;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class InteractionService {
    private final Set<String> processedKeys = ConcurrentHashMap.newKeySet();

    public InteractionResult like(Long postId, String idempotencyKey) {
        return interact(postId, "LIKE", idempotencyKey);
    }

    public InteractionResult favorite(Long postId, String idempotencyKey) {
        return interact(postId, "FAVORITE", idempotencyKey);
    }

    private InteractionResult interact(Long targetId, String type, String idempotencyKey) {
        String key = normalizeKey(type, targetId, idempotencyKey);
        boolean firstSeen = processedKeys.add(key);
        return new InteractionResult(targetId, type, true, !firstSeen, key);
    }

    private String normalizeKey(String type, Long targetId, String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            return idempotencyKey;
        }
        return "demo-" + type.toLowerCase() + "-" + targetId;
    }
}

