package io.github.devcircle.user.application;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.user.infrastructure.UserGraphRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserGraphServiceFollowTest {
    @Test
    void missingTargetProfileFailsBeforeClaimingTheIdempotencyKey() {
        UserGraphRepository repository = mock(UserGraphRepository.class);
        UserGraphService service = new UserGraphService(repository);
        when(repository.requireProfile(1L)).thenReturn(profile(1L));
        when(repository.requireProfile(99L)).thenThrow(
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "用户不存在或资料尚未完成同步"));

        assertThatThrownBy(() -> service.follow(1L, 99L, "follow-missing"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        verify(repository, never()).claimOperation(1L, 99L, "FOLLOW", "follow-missing");
    }

    @Test
    void realFollowWritesTheDomainEventAfterTheRelationshipChanges() {
        UserGraphRepository repository = mock(UserGraphRepository.class);
        UserGraphService service = new UserGraphService(repository);
        UserGraphRepository.UserProfile actor = profile(1L);
        UserGraphRepository.UserProfile receiver = profile(2L);
        when(repository.requireProfile(1L)).thenReturn(actor);
        when(repository.requireProfile(2L)).thenReturn(receiver);
        when(repository.claimOperation(1L, 2L, "FOLLOW", "follow-1-2")).thenReturn(true);
        when(repository.follow(1L, 2L)).thenReturn(true);

        var result = service.follow(1L, 2L, "follow-1-2");

        assertThat(result.followed()).isTrue();
        assertThat(result.duplicated()).isFalse();
        verify(repository).writeFollowChanged(1L, actor, 2L, receiver, true);
    }

    private UserGraphRepository.UserProfile profile(long userId) {
        return new UserGraphRepository.UserProfile(userId, "user-" + userId, null, null, 0, 0);
    }
}
