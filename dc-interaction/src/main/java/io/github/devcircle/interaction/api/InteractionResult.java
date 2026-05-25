package io.github.devcircle.interaction.api;

public record InteractionResult(Long targetId, String type, boolean active, boolean duplicated, String idempotencyKey) {
}

