package io.github.devcircle.common.error;

import io.github.devcircle.common.api.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @ParameterizedTest
    @MethodSource("businessStatuses")
    void mapsBusinessErrorsToStableHttpStatuses(ErrorCode code, HttpStatus expectedStatus) {
        ResponseEntity<ApiResponse<Void>> response = handler.business(new BusinessException(code, "safe message"));

        assertThat(response.getStatusCode()).isEqualTo(expectedStatus);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(code.name());
        assertThat(response.getBody().message()).isEqualTo("safe message");
    }

    @Test
    void returnsFirstValidationMessageForEachField() throws Exception {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "request");
        binding.addError(new FieldError("request", "title", "标题不能为空"));
        binding.addError(new FieldError("request", "title", "标题格式错误"));
        binding.addError(new FieldError("request", "content", "正文不能为空"));
        Method method = GlobalExceptionHandlerTest.class.getDeclaredMethod("sampleEndpoint", String.class);
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(
                new MethodParameter(method, 0), binding);

        ResponseEntity<ApiResponse<Void>> response = handler.validation(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.VALIDATION_FAILED.name());
        assertThat(response.getBody().meta()).containsEntry("fieldErrors", Map.of(
                "title", "标题不能为空",
                "content", "正文不能为空"));
    }

    @Test
    void handlesConstraintViolationsWithoutInventingFieldErrors() {
        ResponseEntity<ApiResponse<Void>> response = handler.validation(new ConstraintViolationException(Set.of()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().meta()).isEmpty();
    }

    @Test
    void exposesSafeIllegalArgumentMessage() {
        ResponseEntity<ApiResponse<Void>> response = handler.illegalArgument(new IllegalArgumentException("参数组合无效"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.VALIDATION_FAILED.name());
        assertThat(response.getBody().message()).isEqualTo("参数组合无效");
    }

    @Test
    void sanitizesUnexpectedFailures() {
        ResponseEntity<ApiResponse<Void>> response = handler.unexpected(
                new IllegalStateException("jdbc:mysql://internal-db:3306 secret-token"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.SYSTEM_ERROR.name());
        assertThat(response.getBody().message()).isEqualTo("服务暂时不可用，请稍后重试");
        assertThat(response.getBody().message()).doesNotContain("internal-db", "secret-token");
    }

    private static Stream<Arguments> businessStatuses() {
        return Stream.of(
                Arguments.of(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED),
                Arguments.of(ErrorCode.AUTHENTICATION_FAILED, HttpStatus.UNAUTHORIZED),
                Arguments.of(ErrorCode.TOKEN_REVOKED, HttpStatus.UNAUTHORIZED),
                Arguments.of(ErrorCode.OAUTH_STATE_INVALID, HttpStatus.UNAUTHORIZED),
                Arguments.of(ErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN),
                Arguments.of(ErrorCode.USER_BANNED, HttpStatus.FORBIDDEN),
                Arguments.of(ErrorCode.RESOURCE_NOT_FOUND, HttpStatus.NOT_FOUND),
                Arguments.of(ErrorCode.EMAIL_ALREADY_REGISTERED, HttpStatus.CONFLICT),
                Arguments.of(ErrorCode.OAUTH_ACCOUNT_CONFLICT, HttpStatus.CONFLICT),
                Arguments.of(ErrorCode.DUPLICATE_OPERATION, HttpStatus.CONFLICT),
                Arguments.of(ErrorCode.VERSION_CONFLICT, HttpStatus.CONFLICT),
                Arguments.of(ErrorCode.DEPENDENCY_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE),
                Arguments.of(ErrorCode.OAUTH_NOT_CONFIGURED, HttpStatus.BAD_REQUEST),
                Arguments.of(ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST),
                Arguments.of(ErrorCode.UPLOAD_REJECTED, HttpStatus.BAD_REQUEST),
                Arguments.of(ErrorCode.SYSTEM_ERROR, HttpStatus.BAD_REQUEST));
    }

    @SuppressWarnings("unused")
    private void sampleEndpoint(String value) {
    }
}
