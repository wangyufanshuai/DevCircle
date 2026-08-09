package io.github.devcircle.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.auth.application.JwtService;
import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.common.error.ErrorCode;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper objectMapper) {
        return (request, response, exception) -> {
            String traceId = request.getHeader("X-Trace-Id");
            if (traceId == null || traceId.isBlank()) {
                traceId = UUID.randomUUID().toString();
            }
            String previousTraceId = MDC.get("traceId");
            MDC.put("traceId", traceId);
            try {
                response.setStatus(HttpStatus.UNAUTHORIZED.value());
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.setHeader("X-Trace-Id", traceId);
                objectMapper.writeValue(response.getOutputStream(), ApiResponse.fail(
                        ErrorCode.UNAUTHORIZED.name(), "请先登录后再继续"));
            } finally {
                if (previousTraceId == null) {
                    MDC.remove("traceId");
                } else {
                    MDC.put("traceId", previousTraceId);
                }
            }
        };
    }

    @Bean
    BearerTokenAuthenticationFilter bearerTokenAuthenticationFilter(JwtService jwtService) {
        return new BearerTokenAuthenticationFilter(jwtService);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            BearerTokenAuthenticationFilter bearerTokenAuthenticationFilter,
                                            AuthenticationEntryPoint authenticationEntryPoint) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors.authenticationEntryPoint(authenticationEntryPoint))
                .authorizeHttpRequests(registry -> registry
                        .requestMatchers("/api/auth/**", "/actuator/health", "/actuator/health/**",
                                "/actuator/prometheus", "/internal/ops/outbox/status",
                                "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                        .requestMatchers("/api/me/**").authenticated()
                        .anyRequest().denyAll())
                .addFilterBefore(bearerTokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    static final class BearerTokenAuthenticationFilter extends OncePerRequestFilter {
        private final JwtService jwtService;

        BearerTokenAuthenticationFilter(JwtService jwtService) {
            this.jwtService = jwtService;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (SecurityContextHolder.getContext().getAuthentication() == null
                    && authorization != null && authorization.startsWith("Bearer ")) {
                try {
                    Claims claims = jwtService.parseAccess(authorization.substring("Bearer ".length()));
                    List<SimpleGrantedAuthority> authorities = roles(claims.get("roles", String.class));
                    UsernamePasswordAuthenticationToken authentication =
                            UsernamePasswordAuthenticationToken.authenticated(claims.getSubject(), null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } catch (RuntimeException ignored) {
                    SecurityContextHolder.clearContext();
                }
            }
            filterChain.doFilter(request, response);
        }

        private List<SimpleGrantedAuthority> roles(String roles) {
            if (roles == null || roles.isBlank()) return List.of();
            return Arrays.stream(roles.split(","))
                    .map(String::trim)
                    .filter(role -> !role.isBlank())
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .toList();
        }
    }
}
