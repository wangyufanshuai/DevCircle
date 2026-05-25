package io.github.devcircle.common.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {
    @Test
    void okWrapsDataWithStableCode() {
        ApiResponse<String> response = ApiResponse.ok("devcircle");

        assertThat(response.code()).isEqualTo("OK");
        assertThat(response.message()).isEqualTo("success");
        assertThat(response.data()).isEqualTo("devcircle");
        assertThat(response.timestamp()).isNotNull();
    }
}

