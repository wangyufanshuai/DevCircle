package io.github.devcircle.interaction.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InteractionServiceTest {
    @Test
    void repeatedIdempotencyKeyDoesNotCreateSecondOperation() {
        InteractionService service = new InteractionService();

        var first = service.like(1001L, "like-1001-user-1");
        var second = service.like(1001L, "like-1001-user-1");

        assertThat(first.duplicated()).isFalse();
        assertThat(second.duplicated()).isTrue();
        assertThat(second.active()).isTrue();
    }
}

