package io.github.devcircle.content.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;

public record SaveDraftRequest(
        @Size(max = 160) String title,
        String content,
        List<String> tags,
        String type,
        List<Long> mediaIds,
        List<@Valid DraftMediaInput> media,
        String theme,
        String contentKind,
        String difficulty,
        Boolean solvable,
        String clues,
        String suspects,
        String timeline,
        String question,
        String answerKeywords,
        String solution,
        @Min(0) Long version
) {
}
