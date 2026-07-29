package io.github.devcircle.admin.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ModerationDecisionRequest(
        @NotBlank @Size(max = 512) String reason
) {
}
