package io.github.devcircle.content.application;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.content.api.DraftMediaInput;
import io.github.devcircle.content.api.SaveDraftRequest;
import io.github.devcircle.content.infrastructure.PostRepository;
import io.github.devcircle.content.infrastructure.ReadingProgressRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class PostServiceMediaTest {
    private final PostRepository repository = mock(PostRepository.class);
    private final PostService service = new PostService(repository, mock(ReadingProgressRepository.class));

    @Test
    void structuredMediaRequiresUniqueIdsAltTextAndSingleCover() {
        assertInvalid(List.of(
                new DraftMediaInput(8L, "COVER", "封面", 0),
                new DraftMediaInput(8L, "INLINE", "重复", 1)), "不能重复");
        assertInvalid(List.of(
                new DraftMediaInput(8L, "COVER", "封面一", 0),
                new DraftMediaInput(9L, "COVER", "封面二", 1)), "只能设置一张封面");
        assertInvalid(List.of(new DraftMediaInput(8L, "INLINE", " ", 0)), "替代文本");
        verifyNoInteractions(repository);
    }

    @Test
    void validStructuredMediaIsDelegatedForOwnershipValidationAndPersistence() {
        SaveDraftRequest request = request(List.of(
                new DraftMediaInput(8L, "COVER", "雨夜观测站封面", 0),
                new DraftMediaInput(9L, "INLINE", "桌面上的怀表与证词", 1)));

        service.createDraft(7L, request, "create-media-draft");

        verify(repository).createDraft(7L, request, "create-media-draft");
    }

    private void assertInvalid(List<DraftMediaInput> media, String message) {
        assertThatThrownBy(() -> service.createDraft(7L, request(media), "create-media-draft"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(message);
    }

    private SaveDraftRequest request(List<DraftMediaInput> media) {
        return new SaveDraftRequest("钟楼档案", "正文", List.of("推理"), "IMAGE",
                media.stream().map(DraftMediaInput::mediaId).toList(), media,
                "DEDUCTION", "CASE_FILE", "MEDIUM", true,
                "线索", "嫌疑人", "时间线", "问题", "怀表", "解析", 0L);
    }
}
