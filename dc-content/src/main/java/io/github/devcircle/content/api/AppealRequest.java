package io.github.devcircle.content.api;

import jakarta.validation.constraints.NotBlank;

public record AppealRequest(@NotBlank String reason) {
}
