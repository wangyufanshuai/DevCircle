package io.github.devcircle.content.api;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record CreatePostRequest(@NotBlank String title, @NotBlank String content, List<String> tags) {
}

