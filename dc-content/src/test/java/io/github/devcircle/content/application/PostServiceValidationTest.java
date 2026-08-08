package io.github.devcircle.content.application;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.common.error.ErrorCode;
import io.github.devcircle.content.api.CreatePostRequest;
import io.github.devcircle.content.api.DraftMediaInput;
import io.github.devcircle.content.api.SaveDraftRequest;
import io.github.devcircle.content.infrastructure.PostRepository;
import io.github.devcircle.content.infrastructure.ReadingProgressRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PostServiceValidationTest {
    private PostRepository repository;
    private PostService service;

    @BeforeEach
    void setUp() {
        repository = mock(PostRepository.class);
        service = new PostService(repository, mock(ReadingProgressRepository.class));
    }

    @Test
    void protectedWritesRequireAuthenticatedUserAndBoundedIdempotencyKey() {
        CreatePostRequest post = new CreatePostRequest("钟楼", "正文", List.of("推理"));

        assertFailure(() -> service.create(null, post, "post-key"), ErrorCode.UNAUTHORIZED, "请先登录");
        assertFailure(() -> service.create(0L, post, "post-key"), ErrorCode.UNAUTHORIZED, "请先登录");
        assertFailure(() -> service.create(7L, post, null), ErrorCode.VALIDATION_FAILED, "Idempotency-Key");
        assertFailure(() -> service.create(7L, post, "  "), ErrorCode.VALIDATION_FAILED, "Idempotency-Key");
        assertFailure(() -> service.create(7L, post, "k".repeat(129)), ErrorCode.VALIDATION_FAILED, "长度");

        verifyNoInteractions(repository);
    }

    @Test
    void draftMediaRejectsInvalidCountsIdsRolesAltTextAndOrdering() {
        List<DraftMediaInput> tooMany = IntStream.rangeClosed(1, 21)
                .mapToObj(id -> new DraftMediaInput((long) id, "INLINE", "证物 " + id, id))
                .toList();
        assertMediaFailure(tooMany, "最多关联 20 个媒体");

        List<DraftMediaInput> withNull = new ArrayList<>();
        withNull.add(null);
        assertMediaFailure(withNull, "媒体 ID");
        assertMediaFailure(List.of(new DraftMediaInput(null, "INLINE", "证物", 0)), "媒体 ID");
        assertMediaFailure(List.of(new DraftMediaInput(0L, "INLINE", "证物", 0)), "媒体 ID");
        assertMediaFailure(List.of(new DraftMediaInput(8L, "VIDEO", "证物", 0)), "媒体角色");
        assertMediaFailure(List.of(new DraftMediaInput(8L, "INLINE", null, 0)), "替代文本");
        assertMediaFailure(List.of(new DraftMediaInput(8L, "INLINE", "x".repeat(241), 0)), "替代文本");
        assertMediaFailure(List.of(new DraftMediaInput(8L, "INLINE", "证物", -1)), "排序");

        verifyNoInteractions(repository);
    }

    @Test
    void nullRoleDefaultsToInlineAndEmptyMediaRemainsValid() {
        SaveDraftRequest defaultRole = draft(0L, List.of(new DraftMediaInput(8L, null, "桌上的怀表", null)));
        SaveDraftRequest noMedia = draft(0L, null);

        service.createDraft(7L, defaultRole, "default-role");
        service.createDraft(7L, noMedia, "no-media");

        verify(repository).createDraft(7L, defaultRole, "default-role");
        verify(repository).createDraft(7L, noMedia, "no-media");
    }

    @Test
    void updateRequiresAnExplicitOptimisticVersion() {
        SaveDraftRequest request = draft(null, List.of());

        assertFailure(() -> service.updateDraft(7L, 11L, request, "update-key"),
                ErrorCode.VALIDATION_FAILED, "version");

        verifyNoInteractions(repository);
    }

    @Test
    void appealReasonIsTrimmedAndStrictlyBounded() {
        assertFailure(() -> service.createAppeal(7L, 11L, null, "appeal-null"),
                ErrorCode.VALIDATION_FAILED, "4 至 512");
        assertFailure(() -> service.createAppeal(7L, 11L, " abc ", "appeal-short"),
                ErrorCode.VALIDATION_FAILED, "4 至 512");
        assertFailure(() -> service.createAppeal(7L, 11L, "x".repeat(513), "appeal-long"),
                ErrorCode.VALIDATION_FAILED, "4 至 512");

        when(repository.createAppeal(7L, 11L, "请重新核对证物", "appeal-valid"))
                .thenReturn(java.util.Map.of("appealId", 91L));
        service.createAppeal(7L, 11L, "  请重新核对证物  ", "appeal-valid");

        verify(repository).createAppeal(7L, 11L, "请重新核对证物", "appeal-valid");
    }

    @Test
    void draftListSizeIsClampedWithoutChangingTheUserScope() {
        when(repository.listDrafts(7L, 1)).thenReturn(List.of());
        when(repository.listDrafts(7L, 50)).thenReturn(List.of());

        service.listDrafts(7L, 0);
        service.listDrafts(7L, 500);

        verify(repository).listDrafts(7L, 1);
        verify(repository).listDrafts(7L, 50);
    }

    private void assertMediaFailure(List<DraftMediaInput> media, String message) {
        assertFailure(() -> service.createDraft(7L, draft(0L, media), "media-key"),
                ErrorCode.VALIDATION_FAILED, message);
    }

    private void assertFailure(Runnable action, ErrorCode code, String message) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    org.assertj.core.api.Assertions.assertThat(error.code()).isEqualTo(code);
                    org.assertj.core.api.Assertions.assertThat(error.getMessage()).contains(message);
                });
    }

    private SaveDraftRequest draft(Long version, List<DraftMediaInput> media) {
        List<DraftMediaInput> safeMedia = media;
        List<Long> mediaIds = media == null ? List.of() : media.stream()
                .filter(java.util.Objects::nonNull)
                .map(DraftMediaInput::mediaId)
                .filter(java.util.Objects::nonNull)
                .toList();
        return new SaveDraftRequest("钟楼档案", "正文", List.of("推理"), "IMAGE", mediaIds, safeMedia,
                "DEDUCTION", "CASE_FILE", "MEDIUM", true,
                "线索", "嫌疑人", "时间线", "问题", "怀表", "解析", version);
    }
}
