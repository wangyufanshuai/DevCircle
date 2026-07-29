package io.github.devcircle.file.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record UploadIntentRequest(
        @NotBlank @Size(max = 255) String filename,
        @NotBlank String contentType,
        @Positive long size,
        @Min(1) @Max(20000) Integer width,
        @Min(1) @Max(20000) Integer height,
        @Min(1) @Max(7200) Integer durationSeconds
) {
}
