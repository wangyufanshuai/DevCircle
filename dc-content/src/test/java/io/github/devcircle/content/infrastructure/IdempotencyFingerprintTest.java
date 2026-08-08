package io.github.devcircle.content.infrastructure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyFingerprintTest {
    @Test
    void preservesTheCanonicalHashUsedByExistingIdempotencyRecords() {
        assertThat(IdempotencyFingerprint.of(
                "reading-progress-v1", 18, "reading-section-1", false))
                .isEqualTo("379bd5495dff4c7f75c38eaaa75e954405822fb9c15f79fe3d540698fb13e890");
        assertThat(IdempotencyFingerprint.LEGACY).isEqualTo("0".repeat(64));
    }
}
