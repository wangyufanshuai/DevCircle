package io.github.devcircle.file.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Configuration
public class S3Config {
    private StaticCredentialsProvider credentials(String accessKey, String secretKey) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    }

    @Bean
    S3Client s3Client(
            @Value("${devcircle.s3.endpoint}") String endpoint,
            @Value("${devcircle.s3.access-key}") String accessKey,
            @Value("${devcircle.s3.secret-key}") String secretKey
    ) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(credentials(accessKey, secretKey))
                .build();
    }

    @Bean
    S3Presigner s3Presigner(
            @Value("${devcircle.s3.presign-endpoint:${devcircle.s3.endpoint}}") String endpoint,
            @Value("${devcircle.s3.access-key}") String accessKey,
            @Value("${devcircle.s3.secret-key}") String secretKey
    ) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(credentials(accessKey, secretKey))
                .build();
    }
}
