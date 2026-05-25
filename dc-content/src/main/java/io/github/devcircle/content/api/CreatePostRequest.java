package io.github.devcircle.content.api;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record CreatePostRequest(@NotBlank String title, @NotBlank String content, List<String> tags, String type, List<Long> mediaIds) {
    public CreatePostRequest(@NotBlank String title, @NotBlank String content, List<String> tags) {
        this(title, content, tags, "TEXT", List.of());
    }
}
