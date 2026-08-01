package io.github.devcircle.content.application;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.content.api.CreatePostRequest;
import io.github.devcircle.content.api.ReadingProgressRequest;
import io.github.devcircle.content.api.SaveDraftRequest;
import io.github.devcircle.content.infrastructure.PostRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PostServiceDependencyFailureTest {
    private PostRepository repository;
    private PostService service;
    private DataAccessResourceFailureException outage;

    @BeforeEach
    void setUp() {
        repository = mock(PostRepository.class);
        service = new PostService(repository);
        outage = new DataAccessResourceFailureException(
                "jdbc:mysql://10.0.0.12:3306/dc_content refused the connection");
    }

    @Test
    void publicReadPathsDoNotExposeDatabaseConnectionDetails() {
        when(repository.list(anyInt(), anyInt(), isNull(), isNull(), isNull())).thenThrow(outage);
        when(repository.listCursor(isNull(), anyInt(), anyString(), isNull(), isNull(), isNull())).thenThrow(outage);
        when(repository.detail(11L)).thenThrow(outage);
        when(repository.publicDetail(anyLong(), isNull())).thenThrow(outage);
        when(repository.publicSummaries(any())).thenThrow(outage);
        when(repository.related(anyLong(), anyInt())).thenThrow(outage);

        assertDependency(() -> service.list(1, 20, null));
        assertDependency(() -> service.listCursor(null, 20, "IMAGE", null, null, null));
        assertDependency(() -> service.detail(11L));
        assertDependency(() -> service.publicDetail(11L, null));
        assertDependency(() -> service.publicSummaries(List.of(11L)));
        assertDependency(() -> service.related(11L, 6));
    }

    @Test
    void failedDraftWriteReturnsAnExplicitDependencyError() {
        SaveDraftRequest request = draft(null);
        when(repository.createDraft(7L, request, "draft-key")).thenThrow(outage);

        assertDependency(() -> service.createDraft(7L, request, "draft-key"));
    }

    @Test
    void everyProtectedDatabasePathUsesTheSameFailureContract() {
        CreatePostRequest post = new CreatePostRequest("钟楼", "正文", List.of("推理"));
        SaveDraftRequest draft = draft(0L);
        ReadingProgressRequest progress = new ReadingProgressRequest(45, "reading-section-2", false);
        when(repository.saveReadingProgress(7L, 11L, progress, "progress-key")).thenThrow(outage);
        when(repository.create(7L, post, "post-key")).thenThrow(outage);
        when(repository.listDrafts(7L, 20)).thenThrow(outage);
        when(repository.getDraft(7L, 11L)).thenThrow(outage);
        when(repository.listAudits(7L, 11L)).thenThrow(outage);
        when(repository.updateDraft(7L, 11L, draft, "update-key")).thenThrow(outage);
        when(repository.submitDraft(7L, 11L, 0L, "submit-key")).thenThrow(outage);
        when(repository.createAppeal(7L, 11L, "请重新核对附件", "appeal-key")).thenThrow(outage);

        assertDependency(() -> service.saveReadingProgress(7L, 11L, progress, "progress-key"));
        assertDependency(() -> service.create(7L, post, "post-key"));
        assertDependency(() -> service.listDrafts(7L, 20));
        assertDependency(() -> service.getDraft(7L, 11L));
        assertDependency(() -> service.listAudits(7L, 11L));
        assertDependency(() -> service.updateDraft(7L, 11L, draft, "update-key"));
        assertDependency(() -> service.submitDraft(7L, 11L, 0L, "submit-key"));
        assertDependency(() -> service.createAppeal(7L, 11L, "请重新核对附件", "appeal-key"));
    }

    private SaveDraftRequest draft(Long version) {
        return new SaveDraftRequest(
                "钟楼草稿", "正文", List.of(), "TEXT", List.of(), List.of(),
                "DEDUCTION", "CASE_FILE", "MEDIUM", false,
                "", "", "", "", "", "", version);
    }

    private void assertDependency(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.code()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);
                    assertThat(error.getMessage()).isEqualTo("内容数据服务暂时不可用，请稍后重试");
                    assertThat(error.getMessage()).doesNotContain("10.0.0.12", "jdbc:mysql");
                    assertThat(error.getCause()).isSameAs(outage);
                });
    }
}
