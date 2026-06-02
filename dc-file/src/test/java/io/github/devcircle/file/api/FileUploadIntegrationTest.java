package io.github.devcircle.file.api;

import io.github.devcircle.file.infrastructure.MediaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@Sql(scripts = "classpath:db/migration/V1__file_schema.sql")
class FileUploadIntegrationTest {
    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("dc_file")
            .withUsername("devcircle")
            .withPassword("devcircle");

    @Container
    static final GenericContainer<?> minio = new GenericContainer<>("minio/minio:RELEASE.2024-01-16T16-07-38Z")
            .withEnv("MINIO_ROOT_USER", "minioadmin")
            .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            .withCommand("server", "/data")
            .withExposedPorts(9000);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("devcircle.s3.endpoint", () -> "http://" + minio.getHost() + ":" + minio.getMappedPort(9000));
        registry.add("devcircle.s3.public-base-url", () -> "http://localhost:9000/devcircle");
        registry.add("devcircle.s3.access-key", () -> "minioadmin");
        registry.add("devcircle.s3.secret-key", () -> "minioadmin");
        registry.add("devcircle.s3.bucket", () -> "devcircle");
    }

    @Autowired
    FileController fileController;

    @Autowired
    MediaRepository mediaRepository;

    @Test
    void uploadsImageToMinioAndPersistsMetadata() throws Exception {
        byte[] onePixelPng = new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
                0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
                0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4,
                (byte) 0x89, 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
                0x54, 0x78, (byte) 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
                0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, (byte) 0xB4, 0x00,
                0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, (byte) 0xAE,
                0x42, 0x60, (byte) 0x82
        };
        var file = new MockMultipartFile("file", "cover.png", "image/png", onePixelPng);

        var response = fileController.upload(file, 640, 480, null).data();

        assertThat(response.mediaId()).isPositive();
        assertThat(response.contentType()).isEqualTo("image/png");
        assertThat(response.status()).isEqualTo("PENDING_REVIEW");
        assertThat(response.width()).isEqualTo(640);
        assertThat(response.height()).isEqualTo(480);
        assertThat(mediaRepository.count()).isEqualTo(1L);
    }
}
