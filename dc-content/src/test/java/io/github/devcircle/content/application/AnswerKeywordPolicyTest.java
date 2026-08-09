package io.github.devcircle.content.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.common.error.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnswerKeywordPolicyTest {
    private final AnswerKeywordPolicy policy = new AnswerKeywordPolicy(new ObjectMapper());

    @Test
    void parsesStructuredJsonAndIgnoresBlankValues() {
        assertThat(policy.parse("[\"钟楼暗层\", null, \"  \", \"伪造时间\"]"))
                .containsExactly("钟楼暗层", "伪造时间");
        assertThat(policy.parse(null)).isEmpty();
        assertThat(policy.parse("null")).isEmpty();
    }

    @Test
    void rejectsMalformedKeywordJsonInsteadOfReturningEmptyData() {
        assertThatThrownBy(() -> policy.parse("[\"钟楼暗层\""))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("解谜内容数据异常");
    }

    @Test
    void appliesNormalizedSynonymsAndDecisiveKeywords() {
        assertThat(policy.matches("凶手藏在钟楼隔层并伪造时间", List.of("夹层", "调换时钟"))).isTrue();
        assertThat(policy.matches("只是一个无关答案", List.of("夹层", "调换时钟"))).isFalse();
    }
}
