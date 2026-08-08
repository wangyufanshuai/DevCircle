package io.github.devcircle.admin.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DltReplayRequest(
        @NotBlank @Size(max = 128) String sourceTopic,
        @Min(0) int partition,
        @Min(0) long offset,
        @NotBlank @Size(min = 4, max = 512) String reason
) {
}
