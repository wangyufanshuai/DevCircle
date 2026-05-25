package io.github.devcircle.auth.api;

import io.github.devcircle.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/auth")
class AuthController {
    @PostMapping("/login")
    ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        var roles = "admin".equalsIgnoreCase(request.username()) ? List.of("USER", "ADMIN") : List.of("USER");
        return ApiResponse.ok(new LoginResponse(
                "demo-access-token-" + request.username(),
                "demo-refresh-token-" + request.username(),
                request.username(),
                roles,
                Instant.now().plusSeconds(3600)
        ));
    }

    @PostMapping("/refresh")
    ApiResponse<LoginResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok(new LoginResponse(
                "demo-access-token-refreshed",
                request.refreshToken(),
                "demo",
                List.of("USER"),
                Instant.now().plusSeconds(3600)
        ));
    }

    @GetMapping("/oauth2/providers")
    ApiResponse<List<String>> providers() {
        return ApiResponse.ok(List.of("github", "gitee"));
    }

    record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    record RefreshRequest(@NotBlank String refreshToken) {
    }

    record LoginResponse(String accessToken, String refreshToken, String username, List<String> roles, Instant expiresAt) {
    }
}

