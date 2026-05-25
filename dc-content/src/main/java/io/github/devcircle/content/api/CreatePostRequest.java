package io.github.devcircle.content.api;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record CreatePostRequest(
        @NotBlank String title,
        @NotBlank String content,
        List<String> tags,
        String type,
        List<Long> mediaIds,
        String theme,
        String contentKind,
        String difficulty,
        Boolean solvable,
        String clues,
        String solution
) {
    public CreatePostRequest(@NotBlank String title, @NotBlank String content, List<String> tags) {
        this(title, content, tags, "TEXT", List.of(), "DEDUCTION", "CASE_FILE", "MEDIUM", false, "", "");
    }
}
