package io.github.devcircle.content.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class IdempotencyFingerprint {
    static final String LEGACY = "0".repeat(64);

    private IdempotencyFingerprint() {
    }

    static String of(Object... values) {
        StringBuilder canonical = new StringBuilder();
        for (Object value : values) {
            if (value == null) {
                canonical.append("-1:|");
            } else {
                String text = String.valueOf(value);
                canonical.append(text.length()).append(':').append(text).append('|');
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
