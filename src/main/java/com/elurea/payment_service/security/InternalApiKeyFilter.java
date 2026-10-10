package com.elurea.payment_service.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Authenticates calls from other back-end services (e.g. the order service asking for a refund) that send
 * the shared secret in the X-Internal-Api-Key header. They get the role SERVICE, never ADMIN.
 * Not a Spring bean on purpose: it is only registered inside the security filter chain.
 */
public class InternalApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Api-Key";

    private final byte[] expectedKey;
    private final AuthenticationEntryPoint entryPoint;

    public InternalApiKeyFilter(String expectedKey, AuthenticationEntryPoint entryPoint) {
        this.expectedKey = expectedKey == null || expectedKey.isBlank()
                ? null
                : expectedKey.getBytes(StandardCharsets.UTF_8);
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (provided == null) {
            chain.doFilter(request, response);
            return;
        }

        // Constant-time comparison so the key cannot be guessed byte by byte from response timings.
        if (expectedKey == null || !MessageDigest.isEqual(expectedKey, provided.getBytes(StandardCharsets.UTF_8))) {
            SecurityContextHolder.clearContext();
            entryPoint.commence(request, response, new BadCredentialsException("Invalid internal API key"));
            return;
        }

        var authentication = new PreAuthenticatedAuthenticationToken(
                "internal-service", null, AuthorityUtils.createAuthorityList("ROLE_SERVICE"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        chain.doFilter(request, response);
    }
}
