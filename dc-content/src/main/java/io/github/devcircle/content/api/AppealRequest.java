package io.github.devcircle.content.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AppealRequest(@NotBlank @Size(min = 4, max = 512) String reason) {
}
