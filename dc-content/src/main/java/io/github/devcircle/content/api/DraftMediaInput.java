package io.github.devcircle.content.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DraftMediaInput(
        @NotNull Long mediaId,
        @Pattern(regexp = "COVER|INLINE") String role,
        @Size(max = 240) String altText,
        @Min(0) Integer sortOrder
) {
}
