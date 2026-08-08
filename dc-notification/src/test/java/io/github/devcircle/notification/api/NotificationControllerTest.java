package io.github.devcircle.notification.api;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.notification.infrastructure.NotificationRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NotificationControllerTest {
    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final NotificationController controller = new NotificationController(repository);

    @Test
    void returnsOpaqueCursorOnlyWhenAnotherPageExists() {
        when(repository.list(7L, "AUDIT", null, 3)).thenReturn(List.of(view(30L), view(20L), view(10L)));

        var response = controller.list(7L, "AUDIT", null, 2);

        assertThat(response.data()).extracting(NotificationView::id).containsExactly(30L, 20L);
        assertThat(response.meta()).containsEntry("hasMore", true).containsEntry("nextCursor", "20");
        verify(repository).list(7L, "AUDIT", null, 3);
    }

    @Test
    void exactFinalPageDoesNotAdvertiseMoreResults() {
        when(repository.list(7L, null, 20L, 3)).thenReturn(List.of(view(19L), view(18L)));

        var response = controller.list(7L, null, 20L, 2);

        assertThat(response.data()).extracting(NotificationView::id).containsExactly(19L, 18L);
        assertThat(response.meta()).containsEntry("hasMore", false).containsEntry("nextCursor", "");
    }

    @Test
    void normalizesSupportedFiltersAndRejectsInvalidRequestIdentity() {
        when(repository.list(7L, "FOLLOW", null, 21)).thenReturn(List.of());

        controller.list(7L, " follow ", null, 20);
        verify(repository).list(7L, "FOLLOW", null, 21);

        assertCode(() -> controller.list(0L, null, null, 20), ErrorCode.UNAUTHORIZED);
        assertCode(() -> controller.list(7L, "unknown", null, 20), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> controller.list(7L, null, 0L, 20), ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void readWritesValidateKeysAndExposeDuplicateState() {
        when(repository.markRead(7L, 12L, "read-key")).thenReturn(true, false);
        when(repository.markAllRead(7L, "all-key")).thenReturn(3L);

        assertThat(controller.markRead(7L, "read-key", 12L).data())
                .containsEntry("read", true).containsEntry("duplicateRequest", false);
        assertThat(controller.markRead(7L, "read-key", 12L).data())
                .containsEntry("duplicateRequest", true);
        assertThat(controller.markAllRead(7L, "all-key").data()).containsEntry("updated", 3L);

        assertCode(() -> controller.markRead(7L, " ", 12L), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> controller.markRead(7L, "key", 0L), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> controller.markAllRead(null, "key"), ErrorCode.UNAUTHORIZED);
    }

    @Test
    void invalidReadRequestDoesNotReachRepository() {
        NotificationRepository isolatedRepository = mock(NotificationRepository.class);
        NotificationController isolatedController = new NotificationController(isolatedRepository);

        assertCode(() -> isolatedController.markRead(7L, "x".repeat(129), 12L), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> isolatedController.markAllRead(-1L, "key"), ErrorCode.UNAUTHORIZED);

        verifyNoInteractions(isolatedRepository);
    }

    private NotificationView view(long id) {
        return new NotificationView(id, "AUDIT", "审核消息", "内容", false,
                null, "POST", id, "/posts/" + id, Instant.parse("2026-07-30T00:00:00Z"));
    }

    private void assertCode(ThrowingAction action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(expected));
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }
}
