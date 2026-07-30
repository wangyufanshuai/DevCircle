package io.github.devcircle.user.application;

import io.github.devcircle.user.infrastructure.UserGraphRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserGraphServiceProfileTest {
    @Test
    void delegatesBatchProfileLoadingWithoutPerUserQueries() {
        UserGraphRepository repository = mock(UserGraphRepository.class);
        UserGraphService service = new UserGraphService(repository, mock(StringRedisTemplate.class));
        List<Long> ids = List.of(7L, 8L, 7L);
        List<UserGraphRepository.UserProfile> profiles = List.of(
                new UserGraphRepository.UserProfile(7L, "林墨", "记录异常信号", 18, 4),
                new UserGraphRepository.UserProfile(8L, "北辰", "整理证物", 6, 3));
        when(repository.profiles(ids)).thenReturn(profiles);

        assertThat(service.profiles(ids)).containsExactlyElementsOf(profiles);
        verify(repository).profiles(ids);
    }
}
