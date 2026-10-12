package io.github.devcircle.file.api;

import io.github.devcircle.file.infrastructure.MediaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FileUploadIntegrationTest {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("dc_file").withUsername("devcircle").withPassword("devcircle");
    @Container
    static final GenericContainer<?> minio = new GenericContainer<>(System.getenv().getOrDefault(
            "DEVCIRCLE_TEST_MINIO_IMAGE", "minio/minio:RELEASE.2024-01-16T16-07-38Z"))
            .withEnv("MINIO_ROOT_USER", "minioadmin").withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            .withCommand("server", "/data").withExposedPorts(9000);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("devcircle.s3.endpoint", () -> "http://" + minio.getHost() + ":" + minio.getMappedPort(9000));
        registry.add("devcircle.s3.presign-endpoint", () -> "http://" + minio.getHost() + ":" + minio.getMappedPort(9000));
        registry.add("devcircle.s3.public-base-url", () -> "http://internal-minio/devcircle");
        registry.add("devcircle.s3.access-key", () -> "minioadmin");
        registry.add("devcircle.s3.secret-key", () -> "minioadmin");
        registry.add("devcircle.s3.bucket", () -> "devcircle");
    }

    @Autowired FileController fileController;
    @Autowired MediaRepository mediaRepository;
    @Autowired JdbcClient jdbcClient;

    @Test
    void presignedUploadIsValidatedBeforeCompletion() throws Exception {
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0, 0, 0, 0, 0, 0, 0, 0};
        var intent = fileController.createIntent(42L, "intent-1",
                new UploadIntentRequest("cover.png", "image/png", png.length, 640, 480, null)).data();

        HttpResponse<Void> upload = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(intent.uploadUrl()))
                .header("Content-Type", "image/png")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(png)).build(), HttpResponse.BodyHandlers.discarding());
        assertThat(upload.statusCode()).isBetween(200, 299);

        FileUploadResult result = fileController.complete(42L, "complete-1", intent.mediaId()).data();
        assertThat(result.mediaId()).isPositive();
        assertThat(result.status()).isEqualTo("PENDING_REVIEW");
        assertThat(result.url()).isEmpty();
        assertThat(mediaRepository.count()).isEqualTo(1L);
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM file_idempotency_record
                        WHERE idempotency_key IN ('intent-1', 'complete-1')
                          AND request_fingerprint <> :legacy
                        """).param("legacy", "0".repeat(64)).query(Long.class).single()).isEqualTo(2L);
    }
}
