package io.github.devcircle.common.http;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

public final class BoundedRestClientFactory {
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(1);
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(2);

    private BoundedRestClientFactory() {
    }

    public static RestClient create(String baseUrl) {
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        RestClient.Builder builder = RestClient.builder().baseUrl(normalizedBaseUrl);
        customize(builder);
        return builder.build();
    }

    public static void customize(RestClient.Builder builder) {
        customize(builder, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    static RestClient create(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        RestClient.Builder builder = RestClient.builder().baseUrl(normalizedBaseUrl);
        customize(builder, connectTimeout, readTimeout);
        return builder.build();
    }

    static void customize(RestClient.Builder builder, Duration connectTimeout, Duration readTimeout) {
        if (builder == null) throw new IllegalArgumentException("RestClient.Builder must not be null");
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(readTimeout, "readTimeout");
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        builder.requestFactory(requestFactory);
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Service base URL must not be blank");
        }
        return baseUrl.strip().replaceAll("/+$", "");
    }

    private static void requirePositive(Duration timeout, String field) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
