package io.github.devcircle.content.domain;

import io.github.devcircle.common.error.BusinessException;
import io.github.devcircle.content.api.DraftMediaView;
import io.github.devcircle.content.api.DraftView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DraftSubmissionValidatorTest {
    @Test
    void acceptsAttachedInlineMediaAndIgnoresExamplesInsideCode() {
        DraftView draft = draft("""
                正文线索。

                ![停摆的怀表](media://8)

                `![示例](https://example.com/example.png)`

                ```markdown
                ![代码示例](https://example.com/not-rendered.png)
                ```
                """, List.of(
                media(7L, "COVER", "雨夜中的钟楼"),
                media(8L, "INLINE", "证物桌上停摆的怀表")));

        assertThatCode(() -> DraftSubmissionValidator.validate(draft)).doesNotThrowAnyException();
    }

    @Test
    void rejectsRemoteRawAndUnattachedMediaReferences() {
        assertInvalid("![外部图片](https://tracker.example/pixel.png)", List.of(), "只允许引用本人上传");
        assertInvalid("<img src=\"https://tracker.example/pixel.png\">", List.of(), "不支持直接嵌入 HTML");
        assertInvalid("![他人的附件](media://99)", List.of(), "未关联到本草稿");
    }

    @Test
    void requiresInlinePlacementAltTextAndSeparateCoverRole() {
        assertInvalid("正文", List.of(media(8L, "INLINE", "证物")), "未插入文章");
        assertInvalid("![封面](media://8)", List.of(media(8L, "COVER", "封面")), "封面不能作为正文媒体");
        assertInvalid("![证物](media://8)", List.of(media(8L, "INLINE", " ")), "替代文本");
    }

    private void assertInvalid(String content, List<DraftMediaView> media, String message) {
        assertThatThrownBy(() -> DraftSubmissionValidator.validate(draft(content, media)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(message);
    }

    private DraftMediaView media(Long id, String role, String altText) {
        return new DraftMediaView(id, role, altText, 0, "image/png");
    }

    private DraftView draft(String content, List<DraftMediaView> media) {
        return new DraftView(11L, 2L, "钟楼档案", content, List.of("推理"), "IMAGE",
                media.stream().map(DraftMediaView::mediaId).toList(), media,
                "DEDUCTION", "CASE_FILE", "MEDIUM", true,
                "线索", "嫌疑人", "时间线", "问题", "怀表", "解析",
                "DRAFT", 3L, Instant.parse("2026-07-30T00:00:00Z"), null, null);
    }
}
