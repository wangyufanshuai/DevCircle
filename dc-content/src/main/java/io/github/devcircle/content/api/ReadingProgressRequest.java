package io.github.devcircle.content.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record ReadingProgressRequest(
        @Min(0) @Max(100) int progressPercent,
        @Size(max = 160) String anchor,
        boolean completed
) {
}
