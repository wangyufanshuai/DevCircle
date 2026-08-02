package io.github.devcircle.gateway;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AggregateRouteContractTest {
    @Test
    void contentRequestsCannotBypassTheContentAggregateFacade() throws IOException {
        String configuration;
        try (InputStream resource = getClass().getClassLoader()
                .getResourceAsStream("application-aggregate.yml")) {
            assertThat(resource).as("aggregate Gateway configuration").isNotNull();
            configuration = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(configuration).contains("id: content-aggregate")
                .contains("Path=/api/posts/**,/api/deduction/**,/api/content/**,/api/comments/**,/api/files/**,/api/admin/**")
                .doesNotContain("id: content-comments")
                .doesNotContain("DC_COMMENT_URI:http://localhost:8104");
    }
}
