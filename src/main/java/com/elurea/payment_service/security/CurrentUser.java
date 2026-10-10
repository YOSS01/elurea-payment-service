package com.elurea.payment_service.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Optional;
import java.util.UUID;

/**
 * Access to the authenticated caller, plus the ownership checks shared by the services.
 */
public final class CurrentUser {

    public static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    private CurrentUser() {
    }

    /** Id of the caller, or empty for anonymous requests. */
    public static Optional<UUID> id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            return Optional.of(UUID.fromString(token.getToken().getSubject()));
        }
        return Optional.empty();
    }

    /** Id of the caller; fails with 401 for anonymous requests. */
    public static UUID requireId() {
        return id().orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Authentication is required"));
    }

    /**
     * The caller's raw bearer token, forwarded to the user service so that it applies its own
     * ownership rules (a customer can only read their own addresses).
     */
    public static String requireToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            return token.getToken().getTokenValue();
        }
        throw new AuthenticationCredentialsNotFoundException("Authentication is required");
    }

    public static boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> ADMIN_AUTHORITY.equals(authority.getAuthority()));
    }

    /** Lets administrators through; everyone else may only act on resources they own. */
    public static void requireSelfOrAdmin(UUID ownerId) {
        if (!isAdmin() && !ownerId.equals(requireId())) {
            throw new AccessDeniedException("You can only access your own resources");
        }
    }
}
