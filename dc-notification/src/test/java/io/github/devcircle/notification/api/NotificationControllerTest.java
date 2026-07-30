package io.github.devcircle.notification.api;

import io.github.devcircle.common.model.NotificationView;
import io.github.devcircle.notification.infrastructure.NotificationRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

    private NotificationView view(long id) {
        return new NotificationView(id, "AUDIT", "审核消息", "内容", false,
                null, "POST", id, "/posts/" + id, Instant.parse("2026-07-30T00:00:00Z"));
    }
}
