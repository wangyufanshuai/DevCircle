package io.github.devcircle.common.error;

import io.github.devcircle.common.api.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiResponse<Void>> business(BusinessException ex) {
        HttpStatus status = switch (ex.code()) {
            case UNAUTHORIZED, AUTHENTICATION_FAILED, TOKEN_REVOKED, OAUTH_STATE_INVALID -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN, USER_BANNED -> HttpStatus.FORBIDDEN;
            case RESOURCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case EMAIL_ALREADY_REGISTERED, OAUTH_ACCOUNT_CONFLICT, DUPLICATE_OPERATION, VERSION_CONFLICT -> HttpStatus.CONFLICT;
            case DEPENDENCY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(ApiResponse.fail(ex.code().name(), ex.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    ResponseEntity<ApiResponse<Void>> validation(Exception ex) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (ex instanceof MethodArgumentNotValidException argumentException) {
            Map<String, String> fields = new LinkedHashMap<>();
            argumentException.getBindingResult().getFieldErrors()
                    .forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
            meta.put("fieldErrors", fields);
        }
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.VALIDATION_FAILED.name(), "请求参数校验失败", meta));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiResponse<Void>> illegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.VALIDATION_FAILED.name(), ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> unexpected(Exception ex) {
        log.error("Unhandled request failure", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ErrorCode.SYSTEM_ERROR.name(), "服务暂时不可用，请稍后重试"));
    }
}
