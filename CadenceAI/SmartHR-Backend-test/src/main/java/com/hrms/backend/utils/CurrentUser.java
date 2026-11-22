package com.hrms.backend.utils;

import com.hrms.backend.models.User;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

/**
 * Reads WHO is calling from Spring Security's authentication, which JwtAuthenticationFilter filled in
 * after verifying the token's signature. This is the ONLY source of the user id for the AI and
 * events APIs: we never read a user id from the request body, URL or headers.
 */
public final class CurrentUser {
    private CurrentUser() {}

    public static String id(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof User user && user.getId() != null) {
            return user.getId();
        }
        throw new AccessDeniedException("Not authenticated");
    }
}
