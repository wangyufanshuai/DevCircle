package io.github.devcircle.interaction;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InteractionMigrationRelocationTest {
    private static final Map<String, String> CANONICAL_SHA256 = Map.of(
            "V1__interaction_schema.sql", "65f45d15aef277823f793632f25d04295c5d158eaf791f88f298289d8bd9af1f",
            "V2__interaction_outbox.sql", "ca4b3d3212eea049d57416a5d14d3cbca6e4f9a54a09796777ffcf27b75e4d78",
            "V3__outbox_aggregate_ordering.sql", "b7b54c5ee3e40082b1d1f911a44658482e93f550dcb023489b9bf21903fb70cc",
            "V4__interaction_counter_versions.sql", "53912e40f5923562adc77aae73eae2da440c4734e362d4f6ad06a39a47969b2e",
            "V5__optimize_favorite_timeline.sql", "c0193b2f0f2d581c236478354a7656fa4288daed1997bd926437123b3a64ca2f"
    );

    @Test
    void relocatedMigrationsRemainByteIdenticalToThePreEmbeddingHistory() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        for (Map.Entry<String, String> migration : CANONICAL_SHA256.entrySet()) {
            ClassPathResource resource = new ClassPathResource(
                    "db/interaction-migration/" + migration.getKey());
            String actual = HexFormat.of().formatHex(digest.digest(resource.getContentAsByteArray()));

            assertThat(actual)
                    .as("SHA-256 of relocated migration %s", migration.getKey())
                    .isEqualTo(migration.getValue());
        }
    }
}
