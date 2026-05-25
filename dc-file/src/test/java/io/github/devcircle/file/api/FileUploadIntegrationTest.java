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

import java.nio.charset.StandardCharsets;

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
        var file = new MockMultipartFile("file", "cover.png", "image/png", "demo-image".getBytes(StandardCharsets.UTF_8));

        var response = fileController.upload(file, 640, 480, null).data();

        assertThat(response.mediaId()).isPositive();
        assertThat(response.contentType()).isEqualTo("image/png");
        assertThat(response.status()).isEqualTo("PENDING_REVIEW");
        assertThat(response.width()).isEqualTo(640);
        assertThat(response.height()).isEqualTo(480);
        assertThat(mediaRepository.count()).isEqualTo(1L);
    }
}
