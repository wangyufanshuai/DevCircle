package io.github.devcircle.common.security;

import java.util.Set;

public record AuthenticatedUser(Long id, String username, Set<String> roles) {
    public boolean isAdmin() {
        return roles != null && roles.contains("ADMIN");
    }
}

