package io.github.devcircle.interaction.application;

import io.github.devcircle.interaction.infrastructure.InteractionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InteractionServiceTest {
    @Test
    void repeatedIdempotencyKeyDoesNotCreateSecondOperation() {
        InteractionRepository repository = mock(InteractionRepository.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(repository.insertLike(1L, 1001L, "like-1001-user-1"))
                .thenReturn(new InteractionRepository.InteractionWriteResult(true, false))
                .thenReturn(new InteractionRepository.InteractionWriteResult(false, true));
        InteractionService service = new InteractionService(repository, redisTemplate);

        var first = service.like(1L, 1001L, "like-1001-user-1");
        var second = service.like(1L, 1001L, "like-1001-user-1");

        assertThat(first.duplicated()).isFalse();
        assertThat(second.duplicated()).isTrue();
        assertThat(second.active()).isTrue();
    }
}
