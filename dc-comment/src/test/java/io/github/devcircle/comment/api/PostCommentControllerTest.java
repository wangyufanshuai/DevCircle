package io.github.devcircle.comment.api;

import io.github.devcircle.comment.application.CommentService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostCommentControllerTest {
    private final CommentService service = mock(CommentService.class);
    private final PostCommentController controller = new PostCommentController(service);

    @Test
    void returnsCursorOnlyWhenAnotherRootThreadExists() {
        when(service.listThreads(1001L, null, 3, "ADMIN"))
                .thenReturn(List.of(thread(30L), thread(20L), thread(10L)));

        var response = controller.list(1001L, "ADMIN", null, 2);

        assertThat(response.data()).extracting(item -> item.root().id()).containsExactly(30L, 20L);
        assertThat(response.meta()).containsEntry("hasMore", true).containsEntry("nextCursor", "20");
        verify(service).listThreads(1001L, null, 3, "ADMIN");
    }

    @Test
    void exactFinalPageDoesNotAdvertiseMoreThreads() {
        when(service.listThreads(1001L, 20L, 3, null))
                .thenReturn(List.of(thread(19L), thread(18L)));

        var response = controller.list(1001L, null, 20L, 2);

        assertThat(response.meta()).containsEntry("hasMore", false).containsEntry("nextCursor", "");
    }

    private CommentThreadView thread(long id) {
        CommentView root = new CommentView(id, 1001L, null, null, 7L, "林墨", "线索", "VISIBLE", 0,
                Instant.parse("2026-07-30T00:00:00Z"));
        return new CommentThreadView(root, List.of());
    }
}
