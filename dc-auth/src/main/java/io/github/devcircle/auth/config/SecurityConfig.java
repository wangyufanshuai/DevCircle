package io.github.devcircle.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.devcircle.auth.application.JwtService;
import io.github.devcircle.common.api.ApiResponse;
import io.github.devcircle.common.error.ErrorCode;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Configuration
public class SecurityConfig {
    private static final RequestMatcher PUBLIC_USER_BATCH_READ =
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/users/batch");
    private static final RequestMatcher PUBLIC_USER_BATCH_READ_TRAILING_SLASH =
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/users/batch/");
    private static final RequestMatcher PUBLIC_USER_PROFILE_READ =
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/users/{id:[0-9]+}");
    private static final RequestMatcher PUBLIC_USER_PROFILE_READ_TRAILING_SLASH =
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/users/{id:[0-9]+}/");
    private static final RequestMatcher PUBLIC_USER_READ = request ->
            PUBLIC_USER_BATCH_READ.matches(request)
                    || PUBLIC_USER_BATCH_READ_TRAILING_SLASH.matches(request)
                    || PUBLIC_USER_PROFILE_READ.matches(request)
                    || PUBLIC_USER_PROFILE_READ_TRAILING_SLASH.matches(request);
    private static final RequestMatcher INTERACTION_STATE_READ =
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/posts/{id:[0-9]+}/interaction-state");
    private static final RequestMatcher INTERACTION_LIKE_WRITE = request ->
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/posts/{id:[0-9]+}/like").matches(request)
                    || PathPatternRequestMatcher.pathPattern(HttpMethod.DELETE, "/api/posts/{id:[0-9]+}/like")
                    .matches(request);
    private static final RequestMatcher INTERACTION_FAVORITE_WRITE = request ->
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/posts/{id:[0-9]+}/favorite")
                    .matches(request)
                    || PathPatternRequestMatcher.pathPattern(HttpMethod.DELETE, "/api/posts/{id:[0-9]+}/favorite")
                    .matches(request);
    private static final RequestMatcher INTERACTION_API = request ->
            INTERACTION_STATE_READ.matches(request)
                    || INTERACTION_LIKE_WRITE.matches(request)
                    || INTERACTION_FAVORITE_WRITE.matches(request);
    private static final RequestMatcher NOTIFICATION_WEBSOCKET_HANDSHAKE =
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/ws/notifications");

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
                                "/internal/ops/identity/outbox/**",
                                "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                        .requestMatchers(NOTIFICATION_WEBSOCKET_HANDSHAKE).permitAll()
                        .requestMatchers(PUBLIC_USER_READ).permitAll()
                        .requestMatchers(INTERACTION_API).authenticated()
                        .requestMatchers("/api/notifications/**").authenticated()
                        .requestMatchers("/api/users/**").authenticated()
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
                    filterChain.doFilter(new AuthenticatedUserRequest(request, claims.getSubject()), response);
                    return;
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

        /**
         * Treats the verified access-token subject as the only caller identity. This mirrors the
         * Gateway's strip-and-replace behavior and prevents direct aggregate requests from
         * supplying a different X-User-Id while leaving the standalone dc-user rollback path
         * unchanged.
         */
        private static final class AuthenticatedUserRequest extends HttpServletRequestWrapper {
            private static final String USER_ID_HEADER = "X-User-Id";
            private final String userId;

            private AuthenticatedUserRequest(HttpServletRequest request, String userId) {
                super(request);
                this.userId = userId;
            }

            @Override
            public String getHeader(String name) {
                return USER_ID_HEADER.equalsIgnoreCase(name) ? userId : super.getHeader(name);
            }

            @Override
            public Enumeration<String> getHeaders(String name) {
                return USER_ID_HEADER.equalsIgnoreCase(name)
                        ? Collections.enumeration(List.of(userId))
                        : super.getHeaders(name);
            }

            @Override
            public Enumeration<String> getHeaderNames() {
                Set<String> names = new LinkedHashSet<>();
                Enumeration<String> existing = super.getHeaderNames();
                while (existing != null && existing.hasMoreElements()) {
                    String name = existing.nextElement();
                    if (!USER_ID_HEADER.equalsIgnoreCase(name)) {
                        names.add(name);
                    }
                }
                names.add(USER_ID_HEADER);
                return Collections.enumeration(names);
            }
        }
    }
}
